package com.smali_generator.db;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The name to show for each jid that sent something.
 *
 * A group is mostly people the user has never saved, so the address book alone
 * names almost nobody: on the group this was written against, 143 of the 153
 * unnamed senders had a {@code wa_contacts} row and not one of them carried a
 * name in it. The push name -- the "~Name" WhatsApp draws above a stranger's
 * message -- is what fills the gap, and it is cached in
 * {@code wa_contacts.wa_name}.
 *
 * Three sources, in the order a person would want them:
 *
 * <ol>
 *   <li>{@code wa_contacts.display_name} -- what the user chose to call them;</li>
 *   <li>{@code wa_contacts.wa_name} -- what they call themselves;</li>
 *   <li>{@code lid_display_name.display_name} -- whatever WhatsApp would draw
 *       for that LID, which is a last resort and often not a name at all; see
 *       {@link #readDisplayNames}.</li>
 * </ol>
 *
 * Each {@code wa_contacts} lookup is tried on the raw jid and then on the
 * phone jid {@link LidJids} translates it to, because that table is keyed on
 * either depending on how the row arrived.
 *
 * Whatever a source offers, a phone number is never accepted as a name --
 * see {@link #looksLikeANumber}.
 *
 * Best effort like everything that reads WhatsApp's own tables: a schema that
 * moved leaves a name as its digits, which is legible if unlovely.
 */
public final class ParticipantNames {
    private static final String TAG = "PATCH";
    private static final String WA_DB = "wa.db";
    private static final String MSGSTORE_DB = "msgstore.db";

    /**
     * Everything a phone number is made of, masked or not.
     *
     * The mask characters are there because of WhatsApp's phone number privacy:
     * somebody who has not shared their number in a group is stored under the
     * string the app would draw for them, which is their country code, a run of
     * U+2219 and the last two digits -- "+1 (\u2219\u2219\u2219) \u2219\u2219\u2219
     * \u2219\u221930" is WhatsApp's own example of it. The bidi marks are there
     * because a number rendered inside a right-to-left UI carries them.
     */
    private static final Pattern NUMBERISH = Pattern.compile(
            "^[0-9+\\-() .\u00A0\u200E\u200F\u2219\u2022\u00B7\u2024\u22C5]+$");

    /** The characters WhatsApp hides a digit behind. */
    private static final String MASKS = "\u2219\u2022\u00B7\u2024\u22C5";

    /**
     * The fewest digits a phone number is written with anywhere.
     *
     * A floor, not a format: without one, a push name of "8200" is a phone
     * number and the person loses the only name they have.
     */
    private static final int SHORTEST_NUMBER = 7;

    private ParticipantNames() {
    }

    /** Display names for the jids given, keyed by the jid asked about. */
    public static Map<String, String> resolve(Context context, Collection<String> rawJids) {
        Map<String, String> names = new HashMap<>();
        if (context == null || rawJids == null || rawJids.isEmpty()) {
            return names;
        }
        Contacts contacts = readContacts(context);
        Map<String, String> displayNames = readDisplayNames(context);
        int fromBook = 0;
        int fromPush = 0;
        int fromDisplay = 0;
        for (String jid : rawJids) {
            if (jid == null) {
                continue;
            }
            String name = either(contacts.display, jid);
            if (name != null) {
                fromBook++;
            } else {
                name = either(contacts.waName, jid);
                if (name != null) {
                    fromPush++;
                } else {
                    name = displayNames.get(jid);
                    if (name != null) {
                        fromDisplay++;
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
                + fromPush + " from a push name, " + fromDisplay + " from a LID display name; "
                + contacts.numbers + " contact row(s) held a number where a name goes");
        return names;
    }

    /**
     * Whether a stored "name" is really a phone number rather than a name.
     *
     * Two of these reach the name columns and neither belongs in the name half
     * of "number - name": the masked form WhatsApp writes for somebody who has
     * not shared their number, which reads as a redacted number where a push
     * name should be, and the plain number itself, which would print the same
     * digits twice. Dropping both lets the next source -- the push name -- have
     * its turn.
     *
     * Nothing here rejects a name for carrying digits: an emoji-only push name
     * is still a name -- emoji are not letters, so a letters-only test would
     * have thrown them away -- and so is a short number somebody chose to be
     * called by. A mask settles it on its own, whatever the length, because
     * nobody names themselves in hidden digits.
     */
    static boolean looksLikeANumber(String value) {
        if (value == null || !NUMBERISH.matcher(value).matches()) {
            return false;
        }
        int digits = 0;
        for (int i = 0; i < value.length(); i++) {
            char at = value.charAt(i);
            if (MASKS.indexOf(at) >= 0) {
                return true;
            }
            if (Character.isDigit(at)) {
                digits++;
            }
        }
        return digits >= SHORTEST_NUMBER;
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

        /** How many rows carried a number where a name was expected. */
        int numbers;

        void put(Map<String, String> into, String jid, String name) {
            if (jid == null || name == null || name.isEmpty() || into.containsKey(jid)) {
                return;
            }
            if (looksLikeANumber(name)) {
                numbers++;
                return;
            }
            into.put(jid, name);
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
     * Whatever WhatsApp would draw for a LID, by that LID.
     *
     * The join is WhatsApp's own -- {@code lid_display_name.lid_row_id} into
     * {@code jid._id}, keeping only a non-empty name -- so this reads the
     * table the way the app that wrote it does.
     *
     * The table is really the username index: its other column is
     * {@code username}, and WhatsApp's own queries against it look up a LID by
     * username and count how many have one. {@code display_name} is only what
     * to render, and for somebody who has not shared their number that is the
     * masked number, not a name. On the device this was written against every
     * one of its 4,741 rows was a mask -- 13 characters, no letter among them,
     * of the form "972" then a run of U+2219 then two digits -- and this class
     * used to hand them straight to the screen as push names, which is what a
     * reader saw instead of anybody's name. {@link #looksLikeANumber} is what
     * stops that; the table stays because on an account where numbers are not
     * hidden it is a real name, and it is tried last either way.
     *
     * Read whole for the same reason {@link LidJids} is: it is a few thousand
     * short strings against a per-sender query on a 72 MB database, and a
     * group screen asks about every sender at once anyway.
     */
    private static Map<String, String> readDisplayNames(Context context) {
        Map<String, String> names = new HashMap<>();
        int numbers = 0;
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
                    if (jid == null || name == null || name.isEmpty()) {
                        continue;
                    }
                    if (looksLikeANumber(name)) {
                        numbers++;
                        continue;
                    }
                    names.put(jid, name);
                }
            } finally {
                cursor.close();
            }
            // The size is the answer to "why is this person still a number?":
            // a small table means WhatsApp never learned their name either.
            Log.i(TAG, "ParticipantNames: " + names.size() + " LID(s) carry a display name, "
                    + numbers + " carry a number instead");
        } catch (Throwable t) {
            Log.e(TAG, "ParticipantNames: no LID display names, senders fall back to the contact store", t);
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
