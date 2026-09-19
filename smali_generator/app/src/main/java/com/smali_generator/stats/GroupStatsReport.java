package com.smali_generator.stats;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a group's messages add up to, filled in section by section.
 *
 * A section that failed is recorded in {@link #failed} rather than left as
 * zeroes, so the screen can say "unavailable" instead of reporting a number it
 * does not have.
 */
public final class GroupStatsReport {

    /** One person's share of the group. */
    public static final class Participant {
        /** The raw jid, or {@link #ME}/{@link #UNKNOWN} for the two sentinels. */
        public final String key;
        public final boolean isMe;
        public String name;

        public long messages;
        public long images;
        public long videos;
        public long audio;
        public long documents;
        public long stickers;
        public long otherMedia;

        public final Map<String, Long> textEmoji = new HashMap<>();
        public final Map<String, Long> reactionEmoji = new HashMap<>();

        Participant(String key, boolean isMe) {
            this.key = key;
            this.isMe = isMe;
            this.name = isMe ? "You" : key.equals(UNKNOWN) ? "Unknown" : key;
        }
    }

    /** The key standing in for this device's own messages, which carry no sender jid. */
    public static final String ME = "\u0000me";

    /**
     * The key for a message that is definitely not this device's own but
     * whose sender jid did not resolve -- an orphaned foreign key, or a
     * departed member whose jid row was cleaned up. Kept distinct from
     * {@link #ME}: {@code from_me=0} says the message is someone else's, it
     * just cannot be named, and folding it into {@link #ME} would silently
     * hand another sender's messages to "You".
     *
     * Neither sentinel can collide with a real jid: {@code jid.raw_string} is
     * always of the form {@code user@server}, and neither of these strings
     * contains {@code '@'}.
     */
    public static final String UNKNOWN = "\u0000unknown";

    public String subject;
    public long totalMessages;
    public long firstTimestamp;
    public long lastTimestamp;

    public final long[] byHour = new long[24];
    public final long[] byWeekday = new long[7];

    public final Set<String> failed = new HashSet<>();

    private final Map<String, Participant> byKey = new LinkedHashMap<>();

    /** The entry for a sender, created on first sight. */
    public Participant participant(String key, boolean isMe) {
        Participant found = byKey.get(key);
        if (found == null) {
            found = new Participant(key, isMe);
            byKey.put(key, found);
        }
        return found;
    }

    public List<Participant> participants() {
        return new ArrayList<>(byKey.values());
    }
}
