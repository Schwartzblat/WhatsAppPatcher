package com.smali_generator.stats;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.smali_generator.db.LidJids;
import com.smali_generator.db.ParticipantNames;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What one group's messages add up to, read out of WhatsApp's message store.
 *
 * This is the only thing in the patch that opens msgstore.db for its messages
 * rather than its jid map, and it stays off the message pipeline entirely: it
 * runs when the user opens a screen, on a background thread, read-only, and
 * closes the database before returning.
 *
 * Every section is its own query in its own try/catch, so a schema that moved
 * costs one card on the screen rather than the screen. Sections are reported as
 * they finish, cheapest first, because the last of them is proportional to the
 * chat's text and a big group would otherwise be several seconds of nothing.
 */
public final class GroupStatsReader {
    private static final String TAG = "PATCH";
    private static final String MSGSTORE_DB = "msgstore.db";

    /** Reported in this order; EMOJI is last because it is the expensive one. */
    public enum Section { SUMMARY, PARTICIPANTS, WHEN, EMOJI }

    /** Called on the reader's thread as each section completes. */
    public interface Progress {
        void onSection(Section section, Snapshot snapshot);
    }

    /**
     * A copy of the report at the moment a section finished, safe for the
     * main thread to read.
     *
     * GroupStatsReport is the reader thread's own accumulator and keeps being
     * written after a section reports -- nameThem() runs a second time for
     * the reaction query, discovering and naming participants
     * after PARTICIPANTS has already been handed off once. Copying instead of
     * synchronizing means the main thread can never observe a field the
     * reader is still writing, and a later section's copy is simply a newer,
     * complete picture rather than a patch applied onto a shared one.
     */
    public static final class Snapshot {
        public final String subject;
        public final long totalMessages;
        public final long firstTimestamp;
        public final long lastTimestamp;
        public final long[] byHour;
        public final long[] byWeekday;
        public final Set<String> failed;
        public final List<Participant> participants;

        private Snapshot(GroupStatsReport report) {
            subject = report.subject;
            totalMessages = report.totalMessages;
            firstTimestamp = report.firstTimestamp;
            lastTimestamp = report.lastTimestamp;
            byHour = Arrays.copyOf(report.byHour, report.byHour.length);
            byWeekday = Arrays.copyOf(report.byWeekday, report.byWeekday.length);
            failed = Collections.unmodifiableSet(new HashSet<>(report.failed));
            // Sorted once, here, rather than by whoever renders it: every
            // future section's copy inherits the same "most talkative first"
            // order for free.
            List<Participant> copy = new ArrayList<>();
            for (GroupStatsReport.Participant who : byMessages(report)) {
                copy.add(new Participant(who));
            }
            participants = Collections.unmodifiableList(copy);
        }

        /** A value copy of one participant -- never the live, still-mutable instance. */
        public static final class Participant {
            public final String key;
            public final boolean isMe;
            public final String name;
            public final long messages;
            public final long firstMessage;
            public final long joined;
            public final Map<String, Long> textEmoji;
            public final Map<String, Long> reactionEmoji;

            private Participant(GroupStatsReport.Participant who) {
                key = who.key;
                isMe = who.isMe;
                name = who.name;
                messages = who.messages;
                firstMessage = who.firstMessage;
                joined = who.joined;
                textEmoji = Collections.unmodifiableMap(new HashMap<>(who.textEmoji));
                reactionEmoji = Collections.unmodifiableMap(new HashMap<>(who.reactionEmoji));
            }
        }
    }

    private GroupStatsReader() {
    }

    /** Blocking; call off the main thread. */
    public static void run(Context context, String gid, Progress progress) {
        GroupStatsReport report = new GroupStatsReport();
        SQLiteDatabase database = null;
        try {
            File file = context.getDatabasePath(MSGSTORE_DB);
            if (!file.exists()) {
                Log.e(TAG, "GroupStatsReader: " + MSGSTORE_DB + " is not where it was expected");
                allFailed(report, progress);
                return;
            }
            database = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            long[] chat = chat(database, gid, report);
            if (chat == null) {
                Log.e(TAG, "GroupStatsReader: no chat row for " + gid);
                allFailed(report, progress);
                return;
            }
            long chatRowId = chat[0];
            section(Section.SUMMARY, report, progress);
            readParticipants(database, chatRowId, report);
            readJoined(database, chat[1], report);
            nameThem(context, report);
            section(Section.PARTICIPANTS, report, progress);
            readWhen(database, chatRowId, report);
            section(Section.WHEN, report, progress);
            readEmoji(database, chatRowId, report);
            // Again: a reaction can come from someone who never sent a message
            // here, and that participant did not exist when naming first ran.
            nameThem(context, report);
            section(Section.EMOJI, report, progress);
        } catch (Throwable t) {
            Log.e(TAG, "GroupStatsReader: could not read " + MSGSTORE_DB, t);
            allFailed(report, progress);
        } finally {
            close(database);
        }
    }

    /**
     * The chat's row id and the group jid's row id, or null if there is no such chat.
     *
     * Both, because the message tables are keyed on the chat and the membership
     * table on the jid, and one query answers for both.
     */
    private static long[] chat(SQLiteDatabase database, String gid, GroupStatsReport report) {
        Cursor cursor = database.rawQuery(
                "SELECT c._id, c.jid_row_id, c.subject FROM chat c"
                        + " JOIN jid j ON j._id = c.jid_row_id"
                        + " WHERE j.raw_string = ?", new String[]{gid});
        try {
            if (!cursor.moveToNext()) {
                return null;
            }
            report.subject = cursor.getString(2);
            return new long[]{cursor.getLong(0), cursor.getLong(1)};
        } finally {
            cursor.close();
        }
    }

    /**
     * One row per sender, with the window the whole chat spans.
     *
     * message_system is joined out: a group's history is full of "X joined",
     * "subject changed" events that are messages in the table and nobody's
     * contribution to the conversation. available_message_view is what
     * WhatsApp counts against itself, so deleted and hidden rows are already
     * gone.
     */
    private static void readParticipants(SQLiteDatabase database, long chatRowId,
                                         GroupStatsReport report) {
        try {
            Cursor cursor = database.rawQuery(
                    "SELECT m.from_me, IFNULL(j.raw_string, '') AS sender,"
                            + " COUNT(*) AS n, MIN(m.timestamp) AS first_ts, MAX(m.timestamp) AS last_ts"
                            + " FROM available_message_view m"
                            + " LEFT JOIN message_system ms ON ms.message_row_id = m._id"
                            + " LEFT JOIN jid j ON j._id = m.sender_jid_row_id"
                            + " WHERE m.chat_row_id = ? AND ms.message_row_id IS NULL"
                            + " GROUP BY m.from_me, sender",
                    new String[]{String.valueOf(chatRowId)});
            long unresolved = 0;
            try {
                while (cursor.moveToNext()) {
                    boolean isMe = cursor.getInt(0) == 1;
                    String sender = cursor.getString(1);
                    long count = cursor.getLong(2);
                    long first = cursor.getLong(3);
                    long last = cursor.getLong(4);
                    // from_me alone decides ME. A from_me=0 row with no sender
                    // jid -- an orphaned foreign key, a departed member whose
                    // jid row is gone -- is still someone else's message;
                    // folding it into ME on the strength of a failed join
                    // would silently hand another sender's count to "You".
                    String key;
                    if (isMe) {
                        key = GroupStatsReport.ME;
                    } else if (sender == null || sender.isEmpty()) {
                        key = GroupStatsReport.UNKNOWN;
                        unresolved += count;
                    } else {
                        key = sender;
                    }
                    GroupStatsReport.Participant who = report.participant(key, isMe);
                    who.messages += count;
                    // Kept per sender as well as chat-wide: it is what a member
                    // with no recorded join date is dated by.
                    if (first > 0 && (who.firstMessage == 0 || first < who.firstMessage)) {
                        who.firstMessage = first;
                    }
                    report.totalMessages += count;
                    if (first > 0 && (report.firstTimestamp == 0 || first < report.firstTimestamp)) {
                        report.firstTimestamp = first;
                    }
                    if (last > report.lastTimestamp) {
                        report.lastTimestamp = last;
                    }
                }
            } finally {
                cursor.close();
            }
            Log.i(TAG, "GroupStatsReader: " + report.totalMessages + " message(s) from "
                    + report.participants().size() + " sender(s)");
            // Not a failure -- the total and the split from "You" both stay
            // correct -- but silent otherwise, so it gets a line of its own.
            if (unresolved > 0) {
                Log.i(TAG, "GroupStatsReader: " + unresolved + " message(s) from an unresolved sender");
            }
        } catch (Throwable t) {
            Log.e(TAG, "GroupStatsReader: participant counts failed", t);
            report.failed.add(Section.PARTICIPANTS.name());
        }
    }

    /**
     * When each current member was added, from WhatsApp's own membership table.
     *
     * {@code group_participant_user.add_timestamp} is milliseconds -- it is
     * written from the same server-corrected clock the message timestamps come
     * from, not from a protocol field in seconds.
     *
     * Only current members have a row there, and a row carries no date when the
     * membership was synced before WhatsApp kept one, so this is expected to
     * name some of the group and not all of it. A sender it says nothing about
     * keeps a zero and is dated by their first message instead.
     *
     * Not a section of its own and never marked failed: the participant counts
     * are unaffected by this query, and losing it costs one line of one card.
     */
    private static void readJoined(SQLiteDatabase database, long groupJidRowId,
                                   GroupStatsReport report) {
        try {
            Map<String, Long> added = new HashMap<>();
            Cursor cursor = database.rawQuery(
                    "SELECT j.raw_string, gpu.add_timestamp"
                            + " FROM group_participant_user gpu"
                            + " JOIN jid j ON j._id = gpu.user_jid_row_id"
                            + " WHERE gpu.group_jid_row_id = ?"
                            + " AND gpu.add_timestamp > 0",
                    new String[]{String.valueOf(groupJidRowId)});
            try {
                while (cursor.moveToNext()) {
                    String jid = cursor.getString(0);
                    if (jid != null && !jid.isEmpty()) {
                        added.put(jid, cursor.getLong(1));
                    }
                }
            } finally {
                cursor.close();
            }
            int dated = 0;
            for (GroupStatsReport.Participant who : report.participants()) {
                Long when = added.get(who.key);
                if (when == null) {
                    // Same two-form lookup the names and the photos need: the
                    // membership row may be keyed on the phone jid where the
                    // messages name a LID.
                    String phone = LidJids.phoneJid(who.key);
                    if (phone != null && !phone.equals(who.key)) {
                        when = added.get(phone);
                    }
                }
                if (when != null) {
                    who.joined = when;
                    dated++;
                }
            }
            Log.i(TAG, "GroupStatsReader: " + added.size() + " member(s) carry a join date, "
                    + dated + " of them people who have spoken here");
        } catch (Throwable t) {
            Log.e(TAG, "GroupStatsReader: join dates unavailable, members dated by their first message", t);
        }
    }

    /**
     * The clock and the week, in local time.
     *
     * strftime is given seconds, so the stored milliseconds are divided;
     * 'localtime' is what makes "the group talks at night" mean the user's
     * night rather than UTC's.
     */
    private static void readWhen(SQLiteDatabase database, long chatRowId,
                                 GroupStatsReport report) {
        try {
            Cursor cursor = database.rawQuery(
                    "SELECT CAST(strftime('%H', m.timestamp/1000, 'unixepoch', 'localtime') AS INTEGER) AS hour,"
                            + " CAST(strftime('%w', m.timestamp/1000, 'unixepoch', 'localtime') AS INTEGER) AS wday,"
                            + " COUNT(*) AS n"
                            + " FROM available_message_view m"
                            + " LEFT JOIN message_system ms ON ms.message_row_id = m._id"
                            + " WHERE m.chat_row_id = ? AND ms.message_row_id IS NULL"
                            + " AND m.timestamp > 0"
                            + " GROUP BY hour, wday",
                    new String[]{String.valueOf(chatRowId)});
            try {
                while (cursor.moveToNext()) {
                    int hour = cursor.getInt(0);
                    int wday = cursor.getInt(1);
                    long count = cursor.getLong(2);
                    if (hour >= 0 && hour < 24) {
                        report.byHour[hour] += count;
                    }
                    if (wday >= 0 && wday < 7) {
                        report.byWeekday[wday] += count;
                    }
                }
            } finally {
                cursor.close();
            }
        } catch (Throwable t) {
            Log.e(TAG, "GroupStatsReader: activity over time failed", t);
            report.failed.add(Section.WHEN.name());
        }
    }

    /**
     * Emoji typed, and emoji reacted with.
     *
     * Listed separately because they are different acts: reaching for a symbol
     * mid-sentence is not the same as picking one off the reaction tray.
     *
     * The text half is the only query in this class that touches text_data,
     * and it is last for that reason -- it is proportional to everything the
     * group has ever said, where the others are grouped counts an index
     * answers. The cursor is consumed row by row and nothing is materialised.
     *
     * The reaction half does not join message_system: message_add_on rows are
     * never message_system rows themselves (a reaction has its own row, not a
     * message row), and a reaction's target -- message_add_on.parent_message_row_id
     * -- was never seen pointing at a system event on the test account (0 of
     * 1160 reactions), which matches the UI: WhatsApp does not offer the
     * reaction tray on a "so-and-so joined" bubble. Nothing to exclude.
     */
    private static void readEmoji(SQLiteDatabase database, long chatRowId,
                                  GroupStatsReport report) {
        try {
            Cursor cursor = database.rawQuery(
                    "SELECT m.from_me, IFNULL(j.raw_string, '') AS sender, m.text_data"
                            + " FROM available_message_view m"
                            + " LEFT JOIN message_system ms ON ms.message_row_id = m._id"
                            + " LEFT JOIN jid j ON j._id = m.sender_jid_row_id"
                            + " WHERE m.chat_row_id = ? AND ms.message_row_id IS NULL"
                            + " AND m.text_data IS NOT NULL AND m.text_data <> ''",
                    new String[]{String.valueOf(chatRowId)});
            try {
                while (cursor.moveToNext()) {
                    boolean isMe = cursor.getInt(0) == 1;
                    String sender = cursor.getString(1);
                    // Same three-way split as readParticipants/readWhat: a
                    // from_me=0 row with no resolvable sender is still someone
                    // else's emoji, not this device's.
                    String key;
                    if (isMe) {
                        key = GroupStatsReport.ME;
                    } else if (sender == null || sender.isEmpty()) {
                        key = GroupStatsReport.UNKNOWN;
                    } else {
                        key = sender;
                    }
                    GroupStatsReport.Participant who = report.participant(key, isMe);
                    Emoji.tally(cursor.getString(2), who.textEmoji);
                }
            } finally {
                cursor.close();
            }
        } catch (Throwable t) {
            Log.e(TAG, "GroupStatsReader: emoji in text failed", t);
            report.failed.add(Section.EMOJI.name());
        }
        try {
            Cursor cursor = database.rawQuery(
                    "SELECT mao.from_me, IFNULL(j.raw_string, '') AS sender,"
                            + " mar.reaction, COUNT(*) AS n"
                            + " FROM message_add_on mao"
                            + " JOIN message_add_on_reaction mar"
                            + " ON mar.message_add_on_row_id = mao._id"
                            + " LEFT JOIN jid j ON j._id = mao.sender_jid_row_id"
                            + " WHERE mao.chat_row_id = ?"
                            + " AND mar.reaction IS NOT NULL AND length(mar.reaction) > 0"
                            + " GROUP BY mao.from_me, sender, mar.reaction",
                    new String[]{String.valueOf(chatRowId)});
            try {
                while (cursor.moveToNext()) {
                    boolean isMe = cursor.getInt(0) == 1;
                    String sender = cursor.getString(1);
                    String reaction = cursor.getString(2);
                    long count = cursor.getLong(3);
                    String key;
                    if (isMe) {
                        key = GroupStatsReport.ME;
                    } else if (sender == null || sender.isEmpty()) {
                        key = GroupStatsReport.UNKNOWN;
                    } else {
                        key = sender;
                    }
                    GroupStatsReport.Participant who = report.participant(key, isMe);
                    Long seen = who.reactionEmoji.get(reaction);
                    who.reactionEmoji.put(reaction, seen == null ? count : seen + count);
                }
            } finally {
                cursor.close();
            }
        } catch (Throwable t) {
            Log.e(TAG, "GroupStatsReader: reactions failed", t);
            report.failed.add(Section.EMOJI.name());
        }
    }

    /** The {@code limit} most-used entries of a tally, commonest first. */
    public static List<Map.Entry<String, Long>> top(Map<String, Long> tally, int limit) {
        List<Map.Entry<String, Long>> all = new ArrayList<>(tally.entrySet());
        Collections.sort(all, new Comparator<Map.Entry<String, Long>>() {
            @Override
            public int compare(Map.Entry<String, Long> left, Map.Entry<String, Long> right) {
                return Long.compare(right.getValue(), left.getValue());
            }
        });
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    /**
     * Labels every sender not yet labelled, as "number - name".
     *
     * Called again after the reaction query, because someone can react in a
     * group without ever having sent a message in it -- that participant is
     * created after this first ran, and would otherwise be the one row on the
     * screen showing a raw jid. Already-named senders are skipped, so the
     * second call reads nothing it does not need.
     */
    private static void nameThem(Context context, GroupStatsReport report) {
        try {
            Set<String> jids = new HashSet<>();
            for (GroupStatsReport.Participant who : report.participants()) {
                if (!who.isMe && who.name.equals(who.key)) {
                    jids.add(who.key);
                }
            }
            if (jids.isEmpty()) {
                return;
            }
            Map<String, String> names = ParticipantNames.resolve(context, jids);
            for (GroupStatsReport.Participant who : report.participants()) {
                if (who.isMe || !who.name.equals(who.key)) {
                    continue;
                }
                String phone = digitsOf(LidJids.phoneJid(who.key));
                String name = names.get(who.key);
                // "number - name", not one or the other: the name is whatever
                // the person calls themselves and two people in a group of
                // hundreds can easily choose the same one, where the number
                // is the thing that actually identifies them.
                // Spaced, because a push name can itself contain a hyphen
                // and an unspaced one reads as part of the number.
                who.name = name != null ? phone + " - " + name : phone;
            }
        } catch (Throwable t) {
            Log.e(TAG, "GroupStatsReader: naming failed, senders stay as digits", t);
        }
    }

    /**
     * The digits of a jid, for a sender the contact store has nothing on.
     *
     * Called on the phone jid rather than the raw one: a LID's digits are
     * an internal identifier that means nothing to anybody, where a phone
     * number at least identifies the person to whoever recognises it.
     */
    private static String digitsOf(String jid) {
        int at = jid.indexOf('@');
        return at > 0 ? jid.substring(0, at) : jid;
    }

    /** Most talkative first; that ordering is the point of the screen. */
    public static List<GroupStatsReport.Participant> byMessages(GroupStatsReport report) {
        List<GroupStatsReport.Participant> all = new ArrayList<>(report.participants());
        Collections.sort(all, new Comparator<GroupStatsReport.Participant>() {
            @Override
            public int compare(GroupStatsReport.Participant left,
                               GroupStatsReport.Participant right) {
                if (left.messages != right.messages) {
                    return Long.compare(right.messages, left.messages);
                }
                return left.name.toLowerCase(Locale.getDefault())
                        .compareTo(right.name.toLowerCase(Locale.getDefault()));
            }
        });
        return all;
    }

    private static void section(Section section, GroupStatsReport report, Progress progress) {
        try {
            progress.onSection(section, new Snapshot(report));
        } catch (Throwable t) {
            Log.e(TAG, "GroupStatsReader: reporting " + section + " failed", t);
        }
    }

    private static void allFailed(GroupStatsReport report, Progress progress) {
        for (Section each : Section.values()) {
            report.failed.add(each.name());
            section(each, report, progress);
        }
    }

    private static void close(SQLiteDatabase database) {
        if (database == null) {
            return;
        }
        try {
            database.close();
        } catch (Throwable t) {
            Log.e(TAG, "GroupStatsReader: close failed", t);
        }
    }
}
