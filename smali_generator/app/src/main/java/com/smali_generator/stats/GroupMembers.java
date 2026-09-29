package com.smali_generator.stats;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.smali_generator.db.LidJids;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Who belongs to which group, read out of WhatsApp's message store.
 *
 * {@code group_participant_user} is the only record of a group's membership --
 * one row per *current* member, keyed on {@code (group_jid_row_id,
 * user_jid_row_id)} where the group key is {@code chat.jid_row_id} and not
 * {@code chat._id}. Membership rather than who has spoken is the whole point
 * here: a group of five hundred where forty have ever posted still has five
 * hundred members, and it is the members two groups have in common that the
 * question is about.
 *
 * Off the message pipeline entirely, like {@link GroupStatsReader}: this runs
 * when somebody opens a screen, on a background thread, read-only, one query
 * at a time, and closes the database before returning.
 *
 * The crossing itself is {@link GroupCrossing}, which has no database in it
 * and is where the rule that decides whether two rows are one person is
 * tested.
 */
public final class GroupMembers {
    private static final String TAG = "PATCH";
    private static final String MSGSTORE_DB = "msgstore.db";

    /**
     * WhatsApp's own preferences, where the two jids this device answers to
     * are kept.
     *
     * Read by name through the framework rather than through any of the app's
     * own wrappers: the file and the keys are strings WhatsApp persists, so
     * they survive the obfuscation that renames everything around them.
     */
    private static final String WA_PREFS = "com.whatsapp_preferences_light";
    private static final String OWN_PHONE_JID = "registration_jid";
    private static final String OWN_LID = "self_lid";

    /**
     * {@code chat.participation_status} for a group this device has left.
     *
     * WhatsApp's own enum is UNSET 0, NOT_PARTICIPANT 1, PARTICIPANT 2,
     * ADMIN 3, SUPER_ADMIN 4. Only the explicit "not a participant" is
     * excluded: the app ships a repair task for this column precisely because
     * it can be wrong, so treating anything other than a definite no as a
     * group worth offering errs towards showing a group too many rather than
     * silently hiding one the user is in.
     */
    private static final int NOT_PARTICIPANT = 1;

    /** LidJids in production; the crossing is given it rather than reaching for it. */
    private static final GroupCrossing.Jids JIDS = LidJids::phoneJid;

    private GroupMembers() {
    }

    /** One group that can be crossed with another. */
    public static final class Group {
        public final String jid;
        public final String name;
        public final int members;

        Group(String jid, String name, int members) {
            this.jid = jid;
            this.name = name;
            this.members = members;
        }
    }

    /** What one crossing came to. */
    public static final class Crossing {
        /**
         * The people in every one of the groups, written as the first group
         * writes them -- which is the form the name and photo lookups want.
         */
        public final List<String> shared;

        /** Each group's own member count, in the order the groups were given. */
        public final int[] sizes;

        /** Whether the message store could be read at all. */
        public final boolean failed;

        Crossing(List<String> shared, int[] sizes, boolean failed) {
            this.shared = Collections.unmodifiableList(shared);
            this.sizes = sizes;
            this.failed = failed;
        }

        /** How many people the first group has, which is what the rest filter. */
        public int anchorSize() {
            return sizes.length == 0 ? 0 : sizes[0];
        }
    }

    /**
     * Every group this device could cross, most members first.
     *
     * Ordered by size rather than by name because the question is which of
     * your groups overlaps, and the big ones are where an overlap comes from;
     * the picker has a search field for finding one by name.
     *
     * A group with no subject is listed by its digits rather than dropped:
     * unlike the chat picker's {@code wa_contacts}, which holds thousands of
     * bare numbers, this table holds only groups, and there are few enough of
     * them that a nameless one is worth offering.
     *
     * Blocking; call off the main thread.
     */
    public static List<Group> groups(Context context) {
        List<Group> groups = new ArrayList<>();
        SQLiteDatabase database = null;
        try {
            database = open(context);
            if (database == null) {
                return groups;
            }
            Cursor cursor = participatingGroups(database);
            try {
                while (cursor.moveToNext()) {
                    String jid = cursor.getString(0);
                    if (jid == null || jid.isEmpty()) {
                        continue;
                    }
                    String subject = cursor.getString(1);
                    String name = subject == null || subject.trim().isEmpty()
                            ? GroupCrossing.digitsOf(jid) : subject;
                    groups.add(new Group(jid, name, cursor.getInt(2)));
                }
            } finally {
                cursor.close();
            }
            Collections.sort(groups, new Comparator<Group>() {
                @Override
                public int compare(Group left, Group right) {
                    if (left.members != right.members) {
                        return Integer.compare(right.members, left.members);
                    }
                    return left.name.toLowerCase(Locale.getDefault())
                            .compareTo(right.name.toLowerCase(Locale.getDefault()));
                }
            });
            Log.i(TAG, "GroupMembers: " + groups.size() + " group(s) available to cross");
            logGroupTypes(database);
        } catch (Throwable t) {
            Log.e(TAG, "GroupMembers: could not list groups", t);
        } finally {
            close(database);
        }
        return groups;
    }

    /**
     * What {@code chat.group_type} holds across the groups on offer.
     *
     * A diagnostic and nothing else -- no value of it is interpreted here.
     * A community's announcement chat is a group in this table like any
     * other, holding every member of the community (1036 of them on the test
     * account), and crossing with it is valid but far less telling than
     * crossing two real groups. WhatsApp leaves those chats out of its own
     * "groups in common", and {@code group_type} is the column it tells them
     * apart by; the enum's values are not written down here because guessing
     * one would mislabel a group. This line is what a later change would read
     * them off, instead of reversing the enum again.
     */
    private static void logGroupTypes(SQLiteDatabase database) {
        try {
            Cursor cursor = database.rawQuery(
                    "SELECT c.group_type, COUNT(*) FROM chat c"
                            + " JOIN jid g ON g._id = c.jid_row_id"
                            + " WHERE g.raw_string LIKE '%@g.us'"
                            + " GROUP BY c.group_type", null);
            try {
                StringBuilder seen = new StringBuilder();
                while (cursor.moveToNext()) {
                    seen.append(' ').append(cursor.getInt(0)).append('=').append(cursor.getInt(1));
                }
                Log.i(TAG, "GroupMembers: chat.group_type over every group, value=count:" + seen);
            } finally {
                cursor.close();
            }
        } catch (Throwable t) {
            Log.i(TAG, "GroupMembers: no chat.group_type on this build", t);
        }
    }

    /**
     * The groups this device still belongs to, with their member counts.
     *
     * Tried with WhatsApp's own participation filter and again without it: the
     * column is one the app itself repairs, and a build that has moved or
     * dropped it should cost the filter rather than the whole feature.
     */
    private static Cursor participatingGroups(SQLiteDatabase database) {
        String select = "SELECT g.raw_string, c.subject, COUNT(gpu.user_jid_row_id)"
                + " FROM chat c"
                + " JOIN jid g ON g._id = c.jid_row_id"
                + " JOIN group_participant_user gpu ON gpu.group_jid_row_id = c.jid_row_id"
                + " WHERE g.raw_string LIKE '%@g.us'";
        String group = " GROUP BY c.jid_row_id";
        try {
            return database.rawQuery(select + " AND c.participation_status <> ?" + group,
                    new String[]{String.valueOf(NOT_PARTICIPANT)});
        } catch (Throwable t) {
            Log.e(TAG, "GroupMembers: no participation_status, listing groups left as well", t);
            return database.rawQuery(select + group, null);
        }
    }

    /**
     * The people every one of these groups holds.
     *
     * One query per group rather than one join over all of them: the number of
     * groups is however many the user has ticked, each query is an index
     * lookup, and a failure then costs the group it belongs to instead of the
     * answer.
     *
     * Blocking; call off the main thread.
     */
    public static Crossing cross(Context context, List<String> gids) {
        int[] sizes = new int[gids == null ? 0 : gids.size()];
        if (gids == null || gids.isEmpty()) {
            return new Crossing(new ArrayList<String>(), sizes, false);
        }
        SQLiteDatabase database = null;
        try {
            database = open(context);
            if (database == null) {
                return new Crossing(new ArrayList<String>(), sizes, true);
            }
            Set<String> self = self(context);
            List<Map<String, String>> groups = new ArrayList<>();
            for (int i = 0; i < gids.size(); i++) {
                Map<String, String> members = GroupCrossing.members(
                        rawMembers(database, gids.get(i)), JIDS, self);
                sizes[i] = members.size();
                groups.add(members);
            }
            List<String> shared = GroupCrossing.shared(groups);
            Log.i(TAG, "GroupMembers: " + shared.size() + " member(s) shared by "
                    + gids.size() + " group(s) of sizes " + java.util.Arrays.toString(sizes));
            return new Crossing(shared, sizes, false);
        } catch (Throwable t) {
            Log.e(TAG, "GroupMembers: could not cross " + gids.size() + " group(s)", t);
            return new Crossing(new ArrayList<String>(), sizes, true);
        } finally {
            close(database);
        }
    }

    /**
     * One group's member jids exactly as its rows store them.
     *
     * A group that cannot be read comes back empty, which makes the crossing
     * empty too -- the honest answer, since nothing is known to be shared with
     * a group nothing is known about.
     */
    private static Set<String> rawMembers(SQLiteDatabase database, String gid) {
        Set<String> jids = new LinkedHashSet<>();
        try {
            Cursor cursor = database.rawQuery(
                    "SELECT u.raw_string FROM group_participant_user gpu"
                            + " JOIN jid g ON g._id = gpu.group_jid_row_id"
                            + " JOIN jid u ON u._id = gpu.user_jid_row_id"
                            + " WHERE g.raw_string = ?", new String[]{gid});
            try {
                while (cursor.moveToNext()) {
                    jids.add(cursor.getString(0));
                }
            } finally {
                cursor.close();
            }
        } catch (Throwable t) {
            Log.e(TAG, "GroupMembers: could not read the members of " + gid, t);
        }
        return jids;
    }

    /**
     * The keys this device's owner appears under, so they can be left out.
     *
     * Both forms, because a group may hold either: WhatsApp's own membership
     * query passes the pair for a single user.
     */
    private static Set<String> self(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(WA_PREFS, Context.MODE_PRIVATE);
            Set<String> self = GroupCrossing.self(
                    prefs.getString(OWN_PHONE_JID, null), prefs.getString(OWN_LID, null), JIDS);
            if (self.isEmpty()) {
                // Not fatal, and not silent: the symptom is one extra row that
                // is in every group, which is easy to mistake for a real
                // result.
                Log.e(TAG, "GroupMembers: this device's own jid is unknown, "
                        + "you will appear in your own results");
            }
            return self;
        } catch (Throwable t) {
            Log.e(TAG, "GroupMembers: could not read " + WA_PREFS, t);
            return Collections.emptySet();
        }
    }

    private static SQLiteDatabase open(Context context) {
        if (context == null) {
            Log.e(TAG, "GroupMembers: no context, nothing can be read");
            return null;
        }
        File file = context.getDatabasePath(MSGSTORE_DB);
        if (!file.exists()) {
            Log.e(TAG, "GroupMembers: " + MSGSTORE_DB + " is not where it was expected");
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
            Log.e(TAG, "GroupMembers: close failed", t);
        }
    }
}
