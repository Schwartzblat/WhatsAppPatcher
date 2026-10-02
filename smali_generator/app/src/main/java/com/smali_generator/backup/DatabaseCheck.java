package com.smali_generator.backup;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Opens a staged database read-only and asks SQLite whether it is sound, before
 * anything displaces the live one. The magic-bytes check in Crypt14 catches a
 * wrong key; this catches a right key over a truncated or corrupt backup, which
 * looks like a database for its first fifteen bytes.
 *
 * Read-only, on a copy, and closed before it returns: this runs inside
 * WhatsApp's process and must not leave a handle on anything.
 *
 * integrity_check reads the whole file, so its cost is proportional to it: a
 * msgstore is ~72 MB on the test device. It is worth paying once per restore to
 * guard chat history, but it must never run on a UI thread; the caller owns that.
 */
public final class DatabaseCheck {

    private static final String TAG = "PATCH";
    /** A real msgstore has hundreds of tables; a handful means something is wrong. */
    private static final int MINIMUM_TABLES = 50;
    /**
     * Tables only a message store has, all attested in msgstore.db. A sound
     * SQLite file is not enough: WhatsApp writes other .crypt14 databases too
     * (wa.db is the contacts), and this is the last check before one could be
     * committed over somebody's chat history. Three of five tolerates a rename
     * or two across releases without refusing a good backup.
     */
    private static final String[] MESSAGE_STORE_TABLES = {
            "message", "chat", "jid", "message_media", "receipt_user"};
    private static final int REQUIRED_MESSAGE_STORE_TABLES = 3;
    /** What SQLite may create beside a database it opens, even read-only. */
    private static final String[] SIDECARS = {"-wal", "-shm", "-journal"};

    private DatabaseCheck() {
    }

    public static final class Report {
        public final boolean usable;
        public final String reason;
        public final int tables;
        public final long messages;

        Report(boolean usable, String reason, int tables, long messages) {
            this.usable = usable;
            this.reason = reason;
            this.tables = tables;
            this.messages = messages;
        }
    }

    public static Report inspect(File database) {
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(database.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);

            String integrity = one(db, "PRAGMA integrity_check");
            if (!"ok".equalsIgnoreCase(integrity)) {
                return new Report(false, "SQLite reports this database as damaged.", 0, 0);
            }

            Set<String> names = tableNames(db);
            int tables = names.size();
            if (tables < MINIMUM_TABLES) {
                return new Report(false, "That database has only " + tables
                        + " tables, so it is not a WhatsApp message store.", tables, 0);
            }
            if (!looksLikeMessageStore(names)) {
                return new Report(false, "That database is sound but does not look like a message"
                        + " store: it has " + recognised(names) + " of the " + MESSAGE_STORE_TABLES.length
                        + " tables a message store has (need " + REQUIRED_MESSAGE_STORE_TABLES + ").",
                        tables, 0);
            }

            // The one number here that is allowed to be unknown: a sound store
            // whose schema renamed or dropped `message` is still a sound store,
            // and refusing it as "could not be opened" would be the wrong reason
            // about a database that opened and passed integrity_check.
            long messages = -1;
            try {
                messages = count(db, "SELECT count(*) FROM message");
            } catch (Throwable t) {
                Log.w(TAG, "DatabaseCheck: no countable message table", t);
            }
            Log.i(TAG, "DatabaseCheck: integrity ok, tables=" + tables + ", messages=" + messages);
            String what = messages >= 0 ? tables + " tables, " + messages + " messages."
                    : tables + " tables.";
            return new Report(true, "Verified: " + what, tables, messages);
        } catch (Throwable t) {
            // A staged file that cannot even be opened is exactly what this is
            // for, so a throw here is an answer rather than a failure.
            Log.w(TAG, "DatabaseCheck: could not inspect the staged database", t);
            return new Report(false, "That file could not be opened as a database.", 0, 0);
        } finally {
            if (db != null) {
                try {
                    db.close();
                } catch (Throwable ignored) {
                    // Nothing useful to do, and this must not mask the report.
                }
            }
            // RestoreSwap.swap renames only the staged file and keys its own
            // sidecar cleanup on the target's names, so a -shm left behind here
            // (a crash mid-check skips close's tidying) would be orphaned in
            // databases/ for good. Tidying, not a gate: failures change nothing.
            for (String suffix : SIDECARS) {
                try {
                    // Derived the way the open was: a relative path resolves
                    // against a CWD of "/" in an app and would miss the orphan.
                    new File(database.getAbsolutePath() + suffix).delete();
                } catch (Throwable ignored) {
                    // See above.
                }
            }
        }
    }

    /**
     * Package-private so the decision is testable; the SQLite call that feeds it is not.
     * Case-insensitive, because SQLite table names are for ASCII.
     */
    static boolean looksLikeMessageStore(Set<String> tableNames) {
        return tableNames != null && recognised(tableNames) >= REQUIRED_MESSAGE_STORE_TABLES;
    }

    private static int recognised(Set<String> tableNames) {
        Set<String> lower = new HashSet<>();
        for (String name : tableNames) {
            if (name != null) {
                lower.add(name.toLowerCase(Locale.ROOT));
            }
        }
        int found = 0;
        for (String wanted : MESSAGE_STORE_TABLES) {
            if (lower.contains(wanted)) {
                found++;
            }
        }
        return found;
    }

    private static Set<String> tableNames(SQLiteDatabase db) {
        Set<String> names = new HashSet<>();
        try (Cursor c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)) {
            while (c.moveToNext()) {
                names.add(c.getString(0));
            }
        }
        return names;
    }

    private static String one(SQLiteDatabase db, String sql) {
        try (Cursor c = db.rawQuery(sql, null)) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    private static long count(SQLiteDatabase db, String sql) {
        try (Cursor c = db.rawQuery(sql, null)) {
            return c.moveToFirst() ? c.getLong(0) : -1;
        }
    }
}
