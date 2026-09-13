package com.smali_generator.db;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The chats the user can pick from, read out of WhatsApp's own contact store.
 *
 * Running inside WhatsApp means its databases are simply this process's own
 * files, so this is an ordinary read-only SQLite open rather than anything
 * clever. Only {@code wa.db} is touched, and only its contact table: the
 * message store is never opened.
 *
 * That one table carries both halves of what a picker needs -- individual
 * chats as {@code ...@s.whatsapp.net} and groups as {@code ...@g.us}, each
 * with the name WhatsApp shows for it.
 *
 * Everything here is best effort. The table is WhatsApp's, not ours, so a
 * rename in some future build has to degrade to an empty list rather than
 * throw: the picker then shows only the chats already chosen, which stays
 * usable enough to undo a selection.
 */
public final class WhatsAppChats {
    private static final String TAG = "PATCH";
    private static final String WA_DB = "wa.db";

    /** Individual chats; anything else is a group, a broadcast or a newsletter. */
    private static final String USER_SUFFIX = "@s.whatsapp.net";
    private static final String GROUP_SUFFIX = "@g.us";

    private WhatsAppChats() {
    }

    /** One pickable chat. */
    public static final class Chat {
        public final String jid;
        public final String name;
        public final boolean isGroup;

        Chat(String jid, String name, boolean isGroup) {
            this.jid = jid;
            this.name = name;
            this.isGroup = isGroup;
        }

        /** The digits of this chat's jid, shown when it is not a group. */
        public String number() {
            return numberOf(jid);
        }

        /** The digits of a jid, for a chat WhatsApp has no name for. */
        static String numberOf(String jid) {
            int at = jid.indexOf('@');
            return at > 0 ? jid.substring(0, at) : jid;
        }
    }

    /**
     * Every chat worth offering, in name order.
     *
     * Nameless rows are left out, and that is most of the table: it holds an
     * entry for every number seen in any group -- thousands of them -- and a
     * list of bare numbers is not something anyone can pick from. The same cut
     * applies to groups, about half of which carry no subject here; those are
     * ones this device has no current membership of, and they would otherwise
     * sort to the top of the list as their raw numeric ids.
     */
    public static List<Chat> load(Context context) {
        List<Chat> chats = new ArrayList<>();
        if (context == null) {
            return chats;
        }
        SQLiteDatabase database = null;
        try {
            File file = context.getDatabasePath(WA_DB);
            if (!file.exists()) {
                Log.e(TAG, "WhatsAppChats: " + WA_DB + " is not where it was expected");
                return chats;
            }
            database = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            Cursor cursor = database.rawQuery(
                    "SELECT jid, display_name FROM wa_contacts"
                            + " WHERE jid IS NOT NULL"
                            + " AND display_name IS NOT NULL AND display_name <> ''", null);
            try {
                while (cursor.moveToNext()) {
                    String jid = cursor.getString(0);
                    if (jid == null || (!jid.endsWith(USER_SUFFIX) && !jid.endsWith(GROUP_SUFFIX))) {
                        continue;
                    }
                    String name = cursor.getString(1);
                    if (name == null || name.isEmpty()) {
                        continue;
                    }
                    chats.add(new Chat(jid, name, jid.endsWith(GROUP_SUFFIX)));
                }
            } finally {
                cursor.close();
            }
            sortByName(chats);
            Log.i(TAG, "WhatsAppChats: " + chats.size() + " chat(s) available to pick from");
        } catch (Throwable t) {
            Log.e(TAG, "WhatsAppChats: could not read " + WA_DB + ", the picker will be empty", t);
        } finally {
            if (database != null) {
                try {
                    database.close();
                } catch (Throwable t) {
                    Log.e(TAG, "WhatsAppChats: close failed", t);
                }
            }
        }
        return chats;
    }

    private static void sortByName(List<Chat> chats) {
        Collections.sort(chats, new Comparator<Chat>() {
            @Override
            public int compare(Chat left, Chat right) {
                return left.name.toLowerCase(Locale.getDefault())
                        .compareTo(right.name.toLowerCase(Locale.getDefault()));
            }
        });
    }

    /**
     * A chat for a jid the contact store has nothing to say about.
     *
     * A selection outlives the contact it was made against -- a contact can be
     * deleted, and a group left -- and a picked chat that stopped appearing in
     * the list would be one the user could see the effect of but not undo.
     */
    public static Chat unknown(String jid) {
        return new Chat(jid, Chat.numberOf(jid), jid.endsWith(GROUP_SUFFIX));
    }
}
