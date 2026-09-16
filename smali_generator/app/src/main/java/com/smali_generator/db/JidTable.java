package com.smali_generator.db;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.smali_generator.utils.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WhatsApp's jid table, and the LID/phone pairs that join rows of it.
 *
 * Two features need this data and they need it differently, and the difference
 * is what this class is split along:
 *
 * <ul>
 * <li>translating a LID to a phone jid wants <b>strings for the pairs only</b>.
 *     That is {@link #phoneByLid()}: one join over {@code jid_map}, a few
 *     thousand short rows, and it is on the path that decides a read receipt;</li>
 * <li>naming a jid the way the search index does wants <b>row ids for every
 *     jid</b>. That is {@link #snapshot()}: the whole {@code jid} table, 88,903
 *     rows and ~220 ms on the device this was measured on, held for the life of
 *     the process.</li>
 * </ul>
 *
 * The two tiers are read and cached independently, and each is read only when
 * something asks for it. That is the point of the split: the receipt path
 * predates the sender search and must not start paying for it, and a user who
 * never enables the search must never load the big tier at all.
 *
 * {@code msgstore.db} is 72 MB and these are the only queries this patch makes
 * against it. The message store proper stays shut.
 *
 * Best effort like everything else that reads WhatsApp's own tables. A schema
 * that moved degrades to an empty answer, which leaves every caller behaving as
 * it did before this class existed.
 */
public final class JidTable {
    private static final String TAG = "PATCH";
    private static final String MSGSTORE_DB = "msgstore.db";

    /** The cheap tier: LID jid string to phone jid string, or null while unread. */
    private static volatile Map<String, String> phoneByLid;

    /** The full tier: every row of {@code jid}, by id and by string, or null while unread. */
    private static volatile Snapshot snapshot;

    private JidTable() {
    }

    /** Everything the full tier reads, as one immutable object. */
    public static final class Snapshot {
        /** Row id to raw jid string, for every row of {@code jid}. */
        public final Map<Long, String> rawById;
        /** The reverse, for looking a jid up by the string a contact row holds. */
        public final Map<String, Long> idByRaw;
        /**
         * Row id to the row ids it is paired with, in both directions.
         *
         * One person is several rows -- a phone jid and one or more LIDs -- and
         * which of them the search index names a message with is not knowable
         * from outside. Carrying the pairing lets a caller name all of them.
         */
        public final Map<Long, List<Long>> linkedIds;

        Snapshot(Map<Long, String> rawById, Map<String, Long> idByRaw, Map<Long, List<Long>> linkedIds) {
            this.rawById = Collections.unmodifiableMap(rawById);
            this.idByRaw = Collections.unmodifiableMap(idByRaw);
            this.linkedIds = Collections.unmodifiableMap(linkedIds);
        }
    }

    /**
     * The LID/phone pairs, read on first use. Never null.
     *
     * Deliberately not served out of {@link #snapshot()}, though the data is a
     * subset of it: this is what a read receipt calls, and answering it would
     * otherwise mean reading the whole jid table on the receipt path for the
     * benefit of a feature the user may never have switched on.
     */
    public static Map<String, String> phoneByLid() {
        Map<String, String> current = phoneByLid;
        if (current == null) {
            current = loadPairs();
        }
        return current;
    }

    /** The whole jid table, read on first use. Never null. */
    public static Snapshot snapshot() {
        Snapshot current = snapshot;
        if (current == null) {
            current = loadSnapshot();
        }
        return current;
    }

    /**
     * Forgets both tiers so the next caller of each rereads it.
     *
     * A contact that got its LID after this process started -- a chat opened
     * for the first time today -- would otherwise be invisible to anything
     * keyed on it until the next restart. The reread is left to whoever asks
     * next, so the screen that calls this never waits on msgstore, and a tier
     * nobody asks for again is never reread at all.
     */
    public static void invalidate() {
        phoneByLid = null;
        snapshot = null;
    }

    private static synchronized Map<String, String> loadPairs() {
        Map<String, String> current = phoneByLid;
        if (current == null) {
            current = readPairs();
            phoneByLid = current;
        }
        return current;
    }

    private static synchronized Snapshot loadSnapshot() {
        Snapshot current = snapshot;
        if (current == null) {
            current = readSnapshot();
            snapshot = current;
        }
        return current;
    }

    private static Map<String, String> readPairs() {
        Map<String, String> pairs = new HashMap<>();
        SQLiteDatabase database = null;
        try {
            database = open("LIDs will not be translated");
            if (database == null) {
                return Collections.unmodifiableMap(pairs);
            }
            // Joined in SQL rather than in this process: without the full tier
            // in memory there is nothing here to join against, and the join is
            // by primary key over the few thousand rows jid_map actually has.
            Cursor cursor = database.rawQuery(
                    "SELECT lid.raw_string, phone.raw_string FROM jid_map pair"
                            + " JOIN jid lid ON lid._id = pair.lid_row_id"
                            + " JOIN jid phone ON phone._id = pair.jid_row_id", null);
            try {
                while (cursor.moveToNext()) {
                    String lid = cursor.getString(0);
                    String phone = cursor.getString(1);
                    if (lid != null && phone != null) {
                        pairs.put(lid, phone);
                    }
                }
            } finally {
                cursor.close();
            }
            Log.i(TAG, "JidTable: " + pairs.size() + " LID(s) carry a phone jid");
        } catch (Throwable t) {
            Log.e(TAG, "JidTable: could not read " + MSGSTORE_DB + ", LIDs will not be translated", t);
        } finally {
            close(database);
        }
        return Collections.unmodifiableMap(pairs);
    }

    private static Snapshot readSnapshot() {
        Map<Long, String> rawById = new HashMap<>();
        Map<String, Long> idByRaw = new HashMap<>();
        Map<Long, List<Long>> linkedIds = new HashMap<>();

        SQLiteDatabase database = null;
        try {
            database = open("no jid is known");
            if (database == null) {
                return new Snapshot(rawById, idByRaw, linkedIds);
            }
            readJids(database, rawById, idByRaw);
            readLinks(database, linkedIds);
            Log.i(TAG, "JidTable: " + rawById.size() + " jid(s), " + linkedIds.size() + " linked row(s)");
        } catch (Throwable t) {
            Log.e(TAG, "JidTable: could not read " + MSGSTORE_DB + ", no jid is known", t);
        } finally {
            close(database);
        }
        return new Snapshot(rawById, idByRaw, linkedIds);
    }

    /** The database, or null with the reason already logged. */
    private static SQLiteDatabase open(String consequence) {
        Context context = Utils.getApplicationContext();
        if (context == null) {
            Log.e(TAG, "JidTable: no application context, " + consequence);
            return null;
        }
        File file = context.getDatabasePath(MSGSTORE_DB);
        if (!file.exists()) {
            Log.e(TAG, "JidTable: " + MSGSTORE_DB + " is not where it was expected");
            return null;
        }
        return SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
    }

    private static void close(SQLiteDatabase database) {
        if (database == null) {
            return;
        }
        try {
            database.close();
        } catch (Throwable t) {
            Log.e(TAG, "JidTable: close failed", t);
        }
    }

    private static void readJids(SQLiteDatabase database, Map<Long, String> rawById, Map<String, Long> idByRaw) {
        Cursor cursor = database.rawQuery("SELECT _id, raw_string FROM jid WHERE raw_string IS NOT NULL", null);
        try {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String raw = cursor.getString(1);
                if (raw != null) {
                    rawById.put(id, raw);
                    // First row wins: the table is keyed on _id, so a repeated
                    // raw_string would be a duplicate row rather than a choice.
                    if (!idByRaw.containsKey(raw)) {
                        idByRaw.put(raw, id);
                    }
                }
            }
        } finally {
            cursor.close();
        }
    }

    private static void readLinks(SQLiteDatabase database, Map<Long, List<Long>> linkedIds) {
        // Row ids only: the full tier already holds every string, so this half
        // of jid_map needs no join.
        Cursor cursor = database.rawQuery("SELECT lid_row_id, jid_row_id FROM jid_map", null);
        try {
            while (cursor.moveToNext()) {
                long lidId = cursor.getLong(0);
                long phoneId = cursor.getLong(1);
                link(linkedIds, lidId, phoneId);
                link(linkedIds, phoneId, lidId);
            }
        } finally {
            cursor.close();
        }
    }

    private static void link(Map<Long, List<Long>> linkedIds, long from, long to) {
        List<Long> linked = linkedIds.get(from);
        if (linked == null) {
            linked = new ArrayList<>(1);
            linkedIds.put(from, linked);
        }
        if (!linked.contains(to)) {
            linked.add(to);
        }
    }
}
