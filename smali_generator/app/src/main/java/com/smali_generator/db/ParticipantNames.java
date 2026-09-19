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
 * Two lookups rather than one, because neither is reliable alone. WhatsApp
 * addresses individual chats by LID, and {@code wa_contacts} on this release
 * does carry {@code @lid} rows -- its own queries filter on
 * {@code jid LIKE '%@lid'} -- but an account can hold none, which is what was
 * measured when the read-receipt picker was written. So a LID is looked up
 * directly first and translated through {@link LidJids} second.
 *
 * Best effort like everything that reads WhatsApp's own tables: a schema that
 * moved leaves every name as its digits, which is legible if unlovely.
 */
public final class ParticipantNames {
    private static final String TAG = "PATCH";
    private static final String WA_DB = "wa.db";

    private ParticipantNames() {
    }

    /** Display names for the jids given, keyed by the jid asked about. */
    public static Map<String, String> resolve(Context context, Collection<String> rawJids) {
        Map<String, String> names = new HashMap<>();
        if (context == null || rawJids == null || rawJids.isEmpty()) {
            return names;
        }
        Map<String, String> table = readContacts(context);
        if (table.isEmpty()) {
            return names;
        }
        for (String jid : rawJids) {
            if (jid == null) {
                continue;
            }
            String name = table.get(jid);
            if (name == null) {
                // The contact store may hold the phone jid rather than the LID
                // the messaging pipeline carries.
                String phone = LidJids.phoneJid(jid);
                if (phone != null && !phone.equals(jid)) {
                    name = table.get(phone);
                }
            }
            if (name != null) {
                names.put(jid, name);
            }
        }
        Log.i(TAG, "ParticipantNames: named " + names.size() + " of " + rawJids.size() + " sender(s)");
        return names;
    }

    /**
     * Every named contact, one row per jid.
     *
     * Read whole and deduped rather than queried per participant: the table is
     * keyed on (jid, raw_contact_id) with no unique constraint on the jid, so
     * one person arrives once per address-book source plus WhatsApp's own rows,
     * and a per-jid query would have to dedupe anyway. Ordered by _id so which
     * row wins is the same on every open.
     */
    private static Map<String, String> readContacts(Context context) {
        Map<String, String> table = new HashMap<>();
        SQLiteDatabase database = null;
        try {
            File file = context.getDatabasePath(WA_DB);
            if (!file.exists()) {
                Log.e(TAG, "ParticipantNames: " + WA_DB + " is not where it was expected");
                return table;
            }
            database = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            Cursor cursor = database.rawQuery(
                    "SELECT jid, display_name FROM wa_contacts"
                            + " WHERE jid IS NOT NULL"
                            + " AND display_name IS NOT NULL AND display_name <> ''"
                            + " ORDER BY _id", null);
            try {
                while (cursor.moveToNext()) {
                    String jid = cursor.getString(0);
                    String name = cursor.getString(1);
                    if (jid != null && name != null && !name.isEmpty() && !table.containsKey(jid)) {
                        table.put(jid, name);
                    }
                }
            } finally {
                cursor.close();
            }
        } catch (Throwable t) {
            Log.e(TAG, "ParticipantNames: could not read " + WA_DB + ", names will be digits", t);
        } finally {
            close(database);
        }
        return table;
    }

    private static void close(SQLiteDatabase database) {
        if (database == null) {
            return;
        }
        try {
            database.close();
        } catch (Throwable t) {
            Log.e(TAG, "ParticipantNames: close failed", t);
        }
    }
}
