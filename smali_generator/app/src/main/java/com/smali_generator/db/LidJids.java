package com.smali_generator.db;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.smali_generator.utils.Utils;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Turns the LID a chat is addressed by into the phone jid a pick is keyed on.
 *
 * WhatsApp has moved one-to-one chats onto LIDs. On the device this was
 * written against, 5167 of 5169 individual chats were addressed as
 * {@code <digits>@lid} and exactly one as {@code <digits>@s.whatsapp.net}, and
 * every jid the messaging pipeline carries -- including the one a read receipt
 * is decided for -- is in that form. {@code wa_contacts}, which the chat
 * picker reads, holds no LID at all: 3187 phone jids and not one {@code @lid}.
 *
 * So the two halves of a per-chat feature name the same chat differently, and
 * a list keyed on one silently never matches the other. Groups escaped it --
 * they are {@code @g.us} on both sides -- which is why picking a group worked
 * and picking a person did nothing.
 *
 * {@code msgstore.db} carries the map in {@code jid_map}, one row per pair of
 * row ids into its {@code jid} table. That is a 72 MB database and this is the
 * only query this patch makes against it: 6k rows joined by primary key, never
 * the message store proper.
 *
 * Best effort like everything else that reads WhatsApp's own tables: a schema
 * that moved degrades to an empty map, which leaves every LID untranslated --
 * exactly the behaviour from before this class existed.
 */
public final class LidJids {
    private static final String TAG = "PATCH";
    private static final String MSGSTORE_DB = "msgstore.db";

    private static final String LID_SERVER = "lid";
    private static final String HOSTED_LID_SUFFIX = ".lid";

    /**
     * LID jid to phone jid, or null while unread.
     *
     * Loaded whole rather than queried per receipt: it is a few thousand short
     * strings, and the alternative is SQLite on the path that decides a
     * receipt.
     */
    private static volatile Map<String, String> phoneByLid;

    private LidJids() {
    }

    /**
     * The phone jid for {@code raw}, or {@code raw} itself.
     *
     * Unchanged for a group, a newsletter or a chat still addressed by phone
     * number, and unchanged for a LID WhatsApp has no phone number for -- that
     * one cannot be picked in the first place, since the picker's own source is
     * the contact store.
     */
    public static String phoneJid(String raw) {
        if (raw == null || !isLid(raw)) {
            return raw;
        }
        Map<String, String> map = phoneByLid;
        if (map == null) {
            map = load();
        }
        String phone = map.get(raw);
        return phone == null ? raw : phone;
    }

    /**
     * Forgets the map so the next lookup rereads it.
     *
     * A contact that got its LID after this process started -- a chat opened
     * for the first time today -- would otherwise not match a pick until the
     * next restart. The reread is left to whatever asks next rather than done
     * here, so the screen that calls this never waits on msgstore.
     */
    public static void invalidate() {
        phoneByLid = null;
    }

    /** {@code @lid}, and the {@code @hosted.lid} variant business chats use. */
    private static boolean isLid(String raw) {
        int at = raw.lastIndexOf('@');
        if (at < 0) {
            return false;
        }
        String server = raw.substring(at + 1);
        return server.equals(LID_SERVER) || server.endsWith(HOSTED_LID_SUFFIX);
    }

    private static synchronized Map<String, String> load() {
        Map<String, String> map = phoneByLid;
        if (map == null) {
            map = read();
            phoneByLid = map;
        }
        return map;
    }

    private static Map<String, String> read() {
        Context context = Utils.getApplicationContext();
        if (context == null) {
            Log.e(TAG, "LidJids: no application context, LIDs will not be translated");
            return Collections.emptyMap();
        }
        Map<String, String> map = new HashMap<>();
        SQLiteDatabase database = null;
        try {
            File file = context.getDatabasePath(MSGSTORE_DB);
            if (!file.exists()) {
                Log.e(TAG, "LidJids: " + MSGSTORE_DB + " is not where it was expected");
                return map;
            }
            database = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            Cursor cursor = database.rawQuery(
                    "SELECT lid.raw_string, phone.raw_string FROM jid_map pair"
                            + " JOIN jid lid ON lid._id = pair.lid_row_id"
                            + " JOIN jid phone ON phone._id = pair.jid_row_id", null);
            try {
                while (cursor.moveToNext()) {
                    String lid = cursor.getString(0);
                    String phone = cursor.getString(1);
                    if (lid != null && phone != null) {
                        map.put(lid, phone);
                    }
                }
            } finally {
                cursor.close();
            }
            Log.i(TAG, "LidJids: " + map.size() + " LID(s) carry a phone jid");
        } catch (Throwable t) {
            Log.e(TAG, "LidJids: could not read " + MSGSTORE_DB + ", LIDs will not be translated", t);
        } finally {
            if (database != null) {
                try {
                    database.close();
                } catch (Throwable t) {
                    Log.e(TAG, "LidJids: close failed", t);
                }
            }
        }
        return map;
    }
}
