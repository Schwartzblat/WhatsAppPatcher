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
 * Two features need this data and they need it differently: translating a LID
 * to a phone jid wants strings, and naming a jid the way the search index does
 * wants row ids. Both come out of the same two tables, so they are read once
 * here rather than twice in two places that could drift apart.
 *
 * {@code msgstore.db} is 72 MB and these are the only queries this patch makes
 * against it: a few thousand short rows from {@code jid}, and the row-id pairs
 * from {@code jid_map}. The message store proper stays shut.
 *
 * Best effort like everything else that reads WhatsApp's own tables. A schema
 * that moved degrades to an empty snapshot, which leaves every caller behaving
 * as it did before this class existed.
 */
public final class JidTable {
    private static final String TAG = "PATCH";
    private static final String MSGSTORE_DB = "msgstore.db";

    private static volatile Snapshot snapshot;

    private JidTable() {
    }

    /** Everything read out of the two tables, as one immutable object. */
    public static final class Snapshot {
        /** Row id to raw jid string, for every row of {@code jid}. */
        public final Map<Long, String> rawById;
        /** The reverse, for looking a jid up by the string a contact row holds. */
        public final Map<String, Long> idByRaw;
        /** LID jid string to phone jid string, which is what {@link LidJids} answers from. */
        public final Map<String, String> phoneByLid;
        /**
         * Row id to the row ids it is paired with, in both directions.
         *
         * One person is several rows -- a phone jid and one or more LIDs -- and
         * which of them the search index names a message with is not knowable
         * from outside. Carrying the pairing lets a caller name all of them.
         */
        public final Map<Long, List<Long>> linkedIds;

        Snapshot(Map<Long, String> rawById, Map<String, Long> idByRaw,
                 Map<String, String> phoneByLid, Map<Long, List<Long>> linkedIds) {
            this.rawById = Collections.unmodifiableMap(rawById);
            this.idByRaw = Collections.unmodifiableMap(idByRaw);
            this.phoneByLid = Collections.unmodifiableMap(phoneByLid);
            this.linkedIds = Collections.unmodifiableMap(linkedIds);
        }
    }

    /** The tables, read on first use. Never null. */
    public static Snapshot snapshot() {
        Snapshot current = snapshot;
        if (current == null) {
            current = load();
        }
        return current;
    }

    /**
     * Forgets the snapshot so the next caller rereads it.
     *
     * A contact that got its LID after this process started -- a chat opened
     * for the first time today -- would otherwise be invisible to anything
     * keyed on it until the next restart. The reread is left to whoever asks
     * next, so the screen that calls this never waits on msgstore.
     */
    public static void invalidate() {
        snapshot = null;
    }

    private static synchronized Snapshot load() {
        Snapshot current = snapshot;
        if (current == null) {
            current = read();
            snapshot = current;
        }
        return current;
    }

    private static Snapshot read() {
        Map<Long, String> rawById = new HashMap<>();
        Map<String, Long> idByRaw = new HashMap<>();
        Map<String, String> phoneByLid = new HashMap<>();
        Map<Long, List<Long>> linkedIds = new HashMap<>();

        Context context = Utils.getApplicationContext();
        if (context == null) {
            Log.e(TAG, "JidTable: no application context, no jid is known");
            return new Snapshot(rawById, idByRaw, phoneByLid, linkedIds);
        }
        SQLiteDatabase database = null;
        try {
            File file = context.getDatabasePath(MSGSTORE_DB);
            if (!file.exists()) {
                Log.e(TAG, "JidTable: " + MSGSTORE_DB + " is not where it was expected");
                return new Snapshot(rawById, idByRaw, phoneByLid, linkedIds);
            }
            database = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            readJids(database, rawById, idByRaw);
            readPairs(database, rawById, phoneByLid, linkedIds);
            Log.i(TAG, "JidTable: " + rawById.size() + " jid(s), " + phoneByLid.size() + " LID pair(s)");
        } catch (Throwable t) {
            Log.e(TAG, "JidTable: could not read " + MSGSTORE_DB + ", no jid is known", t);
        } finally {
            if (database != null) {
                try {
                    database.close();
                } catch (Throwable t) {
                    Log.e(TAG, "JidTable: close failed", t);
                }
            }
        }
        return new Snapshot(rawById, idByRaw, phoneByLid, linkedIds);
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

    private static void readPairs(SQLiteDatabase database, Map<Long, String> rawById,
                                  Map<String, String> phoneByLid, Map<Long, List<Long>> linkedIds) {
        // Joined by row id in this process rather than in SQL: the strings are
        // already in memory from readJids, so jid_map needs no join at all.
        Cursor cursor = database.rawQuery("SELECT lid_row_id, jid_row_id FROM jid_map", null);
        try {
            while (cursor.moveToNext()) {
                long lidId = cursor.getLong(0);
                long phoneId = cursor.getLong(1);
                link(linkedIds, lidId, phoneId);
                link(linkedIds, phoneId, lidId);
                String lid = rawById.get(lidId);
                String phone = rawById.get(phoneId);
                if (lid != null && phone != null) {
                    phoneByLid.put(lid, phone);
                }
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
