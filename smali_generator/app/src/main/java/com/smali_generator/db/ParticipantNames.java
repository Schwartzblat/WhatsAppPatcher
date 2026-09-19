package com.smali_generator.db;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * The name to show for each jid that sent something.
 *
 * A group is mostly people the user has never saved, so the address book alone
 * names almost nobody: on the group this was written against, 143 of the 153
 * unnamed senders had a {@code wa_contacts} row and not one of them carried a
 * name in it. WhatsApp fills that gap with the push name -- the "~Name" it
 * draws above a stranger's message -- and keeps it in {@code msgstore.db}'s
 * {@code lid_display_name}, keyed by the LID, which is the form the messaging
 * pipeline addresses people in. That table is what takes this screen from
 * naming 30 of 183 senders to naming nearly all of them.
 *
 * Three sources, in the order a person would want them:
 *
 * <ol>
 *   <li>{@code wa_contacts.display_name} -- what the user chose to call them;</li>
 *   <li>{@code lid_display_name.display_name} -- what they call themselves;</li>
 *   <li>{@code wa_contacts.wa_name} -- the same thing, cached by the contact
 *       sync rather than the message pipeline, and present for far fewer
 *       people.</li>
 * </ol>
 *
 * Each {@code wa_contacts} lookup is tried on the raw jid and then on the
 * phone jid {@link LidJids} translates it to, because that table is keyed on
 * either depending on how the row arrived.
 *
 * Best effort like everything that reads WhatsApp's own tables: a schema that
 * moved leaves a name as its digits, which is legible if unlovely.
 */
public final class ParticipantNames {
    private static final String TAG = "PATCH";
    private static final String WA_DB = "wa.db";
    private static final String MSGSTORE_DB = "msgstore.db";

    private ParticipantNames() {
    }

    /** Display names for the jids given, keyed by the jid asked about. */
    public static Map<String, String> resolve(Context context, Collection<String> rawJids) {
        Map<String, String> names = new HashMap<>();
        if (context == null || rawJids == null || rawJids.isEmpty()) {
            return names;
        }
        Contacts contacts = readContacts(context);
        Map<String, String> pushNames = readPushNames(context);
        int fromBook = 0;
        int fromPush = 0;
        int fromCache = 0;
        for (String jid : rawJids) {
            if (jid == null) {
                continue;
            }
            String name = either(contacts.display, jid);
            if (name != null) {
                fromBook++;
            } else {
                name = pushNames.get(jid);
                if (name != null) {
                    fromPush++;
                } else {
                    name = either(contacts.waName, jid);
                    if (name != null) {
                        fromCache++;
                    }
                }
            }
            if (name != null) {
                names.put(jid, name);
            }
        }
        // Counted by source: "named 0 of 183" on its own cannot say whether
        // the address book, the LID map or the push-name table is the half
        // that is missing, and that question cost a day once.
        Log.i(TAG, "ParticipantNames: named " + names.size() + " of " + rawJids.size()
                + " sender(s) -- " + fromBook + " from the address book, "
                + fromPush + " from a push name, " + fromCache + " from a cached push name");
        return names;
    }

    /** A lookup tried on the jid itself, then on the phone jid it translates to. */
    private static String either(Map<String, String> table, String jid) {
        String name = table.get(jid);
        if (name != null) {
            return name;
        }
        String phone = LidJids.phoneJid(jid);
        return phone != null && !phone.equals(jid) ? table.get(phone) : null;
    }

    /** The two kinds of name {@code wa_contacts} holds, each by jid. */
    private static final class Contacts {
        final Map<String, String> display = new HashMap<>();
        final Map<String, String> waName = new HashMap<>();

        void put(Map<String, String> into, String jid, String name) {
            if (jid != null && name != null && !name.isEmpty() && !into.containsKey(jid)) {
                into.put(jid, name);
            }
        }
    }

    /**
     * Every contact that has a name of either kind, one entry per jid.
     *
     * Read whole and deduped rather than queried per participant: the table is
     * keyed on (jid, raw_contact_id) with no unique constraint on the jid, so
     * one person arrives once per address-book source plus WhatsApp's own rows,
     * and a per-jid query would have to dedupe anyway. Ordered by _id so which
     * row wins is the same on every open.
     */
    private static Contacts readContacts(Context context) {
        Contacts contacts = new Contacts();
        SQLiteDatabase database = null;
        try {
            File file = context.getDatabasePath(WA_DB);
            if (!file.exists()) {
                Log.e(TAG, "ParticipantNames: " + WA_DB + " is not where it was expected");
                return contacts;
            }
            database = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            if (!readContacts(database, contacts, true)) {
                // wa_name is the newer of the two columns. A release that
                // predates it must still get address-book names rather than a
                // screen of digits, so the query is retried without it.
                Log.e(TAG, "ParticipantNames: no wa_name column, cached push names unavailable");
                readContacts(database, contacts, false);
            }
        } catch (Throwable t) {
            Log.e(TAG, "ParticipantNames: could not read " + WA_DB + ", names will be digits", t);
        } finally {
            close(database, WA_DB);
        }
        return contacts;
    }

    /** Fills {@code contacts}; false if the query itself could not run. */
    private static boolean readContacts(SQLiteDatabase database, Contacts contacts,
                                        boolean withWaName) {
        try {
            Cursor cursor = database.rawQuery(
                    "SELECT jid, display_name" + (withWaName ? ", wa_name" : "")
                            + " FROM wa_contacts WHERE jid IS NOT NULL"
                            + " AND ((display_name IS NOT NULL AND display_name <> '')"
                            + (withWaName ? " OR (wa_name IS NOT NULL AND wa_name <> '')" : "")
                            + ") ORDER BY _id", null);
            try {
                while (cursor.moveToNext()) {
                    String jid = cursor.getString(0);
                    contacts.put(contacts.display, jid, cursor.getString(1));
                    if (withWaName) {
                        contacts.put(contacts.waName, jid, cursor.getString(2));
                    }
                }
            } finally {
                cursor.close();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Push names, by the LID they belong to.
     *
     * The join is WhatsApp's own -- {@code lid_display_name.lid_row_id} into
     * {@code jid._id}, keeping only a non-empty name -- so this reads the
     * table the way the app that wrote it does.
     *
     * Read whole for the same reason {@link LidJids} is: it is a few thousand
     * short strings against a per-sender query on a 72 MB database, and a
     * group screen asks about every sender at once anyway.
     */
    private static Map<String, String> readPushNames(Context context) {
        Map<String, String> names = new HashMap<>();
        SQLiteDatabase database = null;
        try {
            File file = context.getDatabasePath(MSGSTORE_DB);
            if (!file.exists()) {
                Log.e(TAG, "ParticipantNames: " + MSGSTORE_DB + " is not where it was expected");
                return names;
            }
            database = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            Cursor cursor = database.rawQuery(
                    "SELECT j.raw_string, ldn.display_name"
                            + " FROM lid_display_name ldn"
                            + " JOIN jid j ON j._id = ldn.lid_row_id"
                            + " WHERE ldn.display_name IS NOT NULL"
                            + " AND length(ldn.display_name) > 0", null);
            try {
                while (cursor.moveToNext()) {
                    String jid = cursor.getString(0);
                    String name = cursor.getString(1);
                    if (jid != null && name != null && !name.isEmpty()) {
                        names.put(jid, name);
                    }
                }
            } finally {
                cursor.close();
            }
            // The size is the answer to "why is this person still a number?":
            // a small table means WhatsApp never learned their name either.
            Log.i(TAG, "ParticipantNames: " + names.size() + " LID(s) carry a push name");
        } catch (Throwable t) {
            Log.e(TAG, "ParticipantNames: no push names, senders fall back to the address book", t);
        } finally {
            close(database, MSGSTORE_DB);
        }
        return names;
    }

    private static void close(SQLiteDatabase database, String name) {
        if (database == null) {
            return;
        }
        try {
            database.close();
        } catch (Throwable t) {
            Log.e(TAG, "ParticipantNames: closing " + name + " failed", t);
        }
    }
}
