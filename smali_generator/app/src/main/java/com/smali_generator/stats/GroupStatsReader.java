package com.smali_generator.stats;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

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
    public enum Section { SUMMARY, PARTICIPANTS, WHEN, WHAT, EMOJI }

    /** Called on the reader's thread as each section completes. */
    public interface Progress {
        void onSection(Section section, Snapshot snapshot);
    }

    /**
     * A copy of the report at the moment a section finished, safe for the
     * main thread to read.
     *
     * GroupStatsReport is the reader thread's own accumulator and keeps being
     * written after a section reports -- nameThem() runs a second time once
     * Task 8's reaction query exists, discovering and naming participants
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
            public final long images;
            public final long videos;
            public final long audio;
            public final long documents;
            public final long stickers;
            public final long otherMedia;
            public final Map<String, Long> textEmoji;
            public final Map<String, Long> reactionEmoji;

            private Participant(GroupStatsReport.Participant who) {
                key = who.key;
                isMe = who.isMe;
                name = who.name;
                messages = who.messages;
                images = who.images;
                videos = who.videos;
                audio = who.audio;
                documents = who.documents;
                stickers = who.stickers;
                otherMedia = who.otherMedia;
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
            long chatRowId = chatRowId(database, gid, report);
            if (chatRowId < 0) {
                Log.e(TAG, "GroupStatsReader: no chat row for " + gid);
                allFailed(report, progress);
                return;
            }
            section(Section.SUMMARY, report, progress);
            readParticipants(database, chatRowId, report);
            nameThem(context, report);
            section(Section.PARTICIPANTS, report, progress);
            readWhen(database, chatRowId, report);
            section(Section.WHEN, report, progress);
            readWhat(database, chatRowId, report);
            section(Section.WHAT, report, progress);
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

    private static long chatRowId(SQLiteDatabase database, String gid, GroupStatsReport report) {
        Cursor cursor = database.rawQuery(
                "SELECT c._id, c.subject FROM chat c"
                        + " JOIN jid j ON j._id = c.jid_row_id"
                        + " WHERE j.raw_string = ?", new String[]{gid});
        try {
            if (!cursor.moveToNext()) {
                return -1;
            }
            report.subject = cursor.getString(1);
            return cursor.getLong(0);
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
     * The media mix, classified by mime type.
     *
     * Not by message_type: that is a table of undocumented magic numbers which
     * drifts between releases, and a wrong constant misfiles messages silently.
     * A mime type describes itself, and message_media is the row that exists
     * precisely when a message carried a file.
     *
     * The one thing mime cannot tell us is a voice note from a shared audio
     * file -- both are audio/* -- so the screen says "audio" rather than
     * inventing a distinction it cannot make.
     *
     * COUNT(DISTINCT m._id), not COUNT(*): message_media has no unique index
     * on message_row_id, so the join is not guaranteed one row per message,
     * and this counts messages of a given type rather than media rows.
     */
    private static void readWhat(SQLiteDatabase database, long chatRowId,
                                 GroupStatsReport report) {
        try {
            Cursor cursor = database.rawQuery(
                    "SELECT m.from_me, IFNULL(j.raw_string, '') AS sender,"
                            + " IFNULL(mm.mime_type, '') AS mime, COUNT(DISTINCT m._id) AS n"
                            + " FROM available_message_view m"
                            + " JOIN message_media mm ON mm.message_row_id = m._id"
                            + " LEFT JOIN message_system ms ON ms.message_row_id = m._id"
                            + " LEFT JOIN jid j ON j._id = m.sender_jid_row_id"
                            + " WHERE m.chat_row_id = ? AND ms.message_row_id IS NULL"
                            + " GROUP BY m.from_me, sender, mime",
                    new String[]{String.valueOf(chatRowId)});
            try {
                while (cursor.moveToNext()) {
                    boolean isMe = cursor.getInt(0) == 1;
                    String sender = cursor.getString(1);
                    String mime = cursor.getString(2);
                    long count = cursor.getLong(3);
                    // Same three-way split as readParticipants: a from_me=0 row
                    // with no resolvable sender is still someone else's media,
                    // and folding it into ME would silently credit "You" with
                    // media you did not send. readParticipants already logs the
                    // unresolved count for this chat, so this just has to land
                    // honestly on UNKNOWN rather than log it again.
                    String key;
                    if (isMe) {
                        key = GroupStatsReport.ME;
                    } else if (sender == null || sender.isEmpty()) {
                        key = GroupStatsReport.UNKNOWN;
                    } else {
                        key = sender;
                    }
                    GroupStatsReport.Participant who = report.participant(key, isMe);
                    add(who, mime == null ? "" : mime, count);
                }
            } finally {
                cursor.close();
            }
        } catch (Throwable t) {
            Log.e(TAG, "GroupStatsReader: media mix failed", t);
            report.failed.add(Section.WHAT.name());
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

    private static void add(GroupStatsReport.Participant who, String mime, long count) {
        // Stickers are webp images, and checking that first is what keeps them
        // out of the photo count.
        if (mime.equals("image/webp")) {
            who.stickers += count;
        } else if (mime.startsWith("image/")) {
            who.images += count;
        } else if (mime.startsWith("video/")) {
            who.videos += count;
        } else if (mime.startsWith("audio/")) {
            who.audio += count;
        } else if (mime.startsWith("application/") || mime.startsWith("text/")) {
            who.documents += count;
        } else {
            who.otherMedia += count;
        }
    }

    /**
     * Puts a name to every sender not yet named.
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
                String name = names.get(who.key);
                who.name = name != null ? name : digitsOf(who.key);
            }
        } catch (Throwable t) {
            Log.e(TAG, "GroupStatsReader: naming failed, senders stay as digits", t);
        }
    }

    /** The digits of a jid, for a sender the contact store has nothing on. */
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
