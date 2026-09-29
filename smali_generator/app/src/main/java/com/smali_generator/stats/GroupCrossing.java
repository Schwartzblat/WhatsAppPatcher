package com.smali_generator.stats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who two or more groups hold in common.
 *
 * Kept apart from the database entirely so the rule that decides whether two
 * rows are the same person can be tested without a device -- and it is the
 * rule worth testing. WhatsApp addresses one member as {@code <digits>@lid} in
 * one group and {@code <digits>@s.whatsapp.net} in another; its own membership
 * query passes both forms for a single user
 * ({@code WHERE user_jid_row_id IN (?, ?)}). An intersection taken on the
 * stored strings therefore comes back empty and reads as "you have nobody in
 * common", which is a wrong answer that looks like a right one.
 *
 * So a member is crossed on the phone jid {@link Jids} translates them to, and
 * *shown* as the group the screen was opened from writes them. Both halves
 * matter: the phone jid is the only form two groups agree on, and the raw form
 * is what the name and photo lookups are keyed on -- {@code lid_display_name},
 * which names most of a group, answers for a LID and for nothing else.
 */
public final class GroupCrossing {

    /**
     * Turns a LID into the phone jid it stands for.
     *
     * {@code LidJids::phoneJid} in production; an interface here because that
     * one needs a 72 MB database and a device, and this logic needs neither.
     */
    public interface Jids {
        String phoneJid(String raw);
    }

    private GroupCrossing() {
    }

    /**
     * One group's members, keyed by what they cross on and valued by the form
     * this group stores them under.
     *
     * Insertion-ordered, so that what the screen lists is stable between two
     * reads of the same group rather than whatever the map happens to hash to.
     * A member already present under their other form is not replaced: the
     * first spelling seen wins, which for the anchor group is the spelling its
     * own rows use.
     */
    public static Map<String, String> members(Collection<String> rawJids, Jids jids,
                                              Set<String> self) {
        Map<String, String> byKey = new LinkedHashMap<>();
        if (rawJids == null) {
            return byKey;
        }
        for (String raw : rawJids) {
            String key = key(raw, jids);
            if (key == null || self.contains(key) || byKey.containsKey(key)) {
                continue;
            }
            byKey.put(key, raw);
        }
        return byKey;
    }

    /**
     * The people every one of these groups holds, as the first group writes them.
     *
     * No groups is nobody rather than everybody: that is the state of the
     * screen before anything has been picked, and the whole contact list is
     * not an answer to a question nobody has asked yet.
     */
    public static List<String> shared(List<Map<String, String>> groups) {
        List<String> found = new ArrayList<>();
        if (groups == null || groups.isEmpty()) {
            return found;
        }
        Map<String, String> anchor = groups.get(0);
        for (Map.Entry<String, String> member : anchor.entrySet()) {
            boolean inAll = true;
            for (int i = 1; i < groups.size() && inAll; i++) {
                inAll = groups.get(i).containsKey(member.getKey());
            }
            if (inAll) {
                found.add(member.getValue());
            }
        }
        return found;
    }

    /**
     * The keys this device's owner is a member under, so they can be left out.
     *
     * You are in every group you can pick, so without this you survive every
     * intersection and are the one row on the screen that means nothing. Both
     * forms WhatsApp registers -- the number and the LID it issues for it --
     * because a group may hold either, and they collapse to one key here.
     */
    public static Set<String> self(String registrationJid, String selfLid, Jids jids) {
        Set<String> keys = new LinkedHashSet<>();
        addKey(keys, registrationJid, jids);
        addKey(keys, selfLid, jids);
        return keys;
    }

    private static void addKey(Set<String> keys, String raw, Jids jids) {
        String key = key(raw, jids);
        if (key != null) {
            keys.add(key);
        }
    }

    /**
     * What this jid crosses on, or null when it is not somebody.
     *
     * An empty string is the common non-person: an orphaned foreign key leaves
     * one behind, and counted as a member it would be a nameless row present
     * in every group on the device.
     */
    private static String key(String raw, Jids jids) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        int at = trimmed.lastIndexOf('@');
        if (at <= 0 || at == trimmed.length() - 1) {
            return null;
        }
        String phone = jids.phoneJid(trimmed);
        return phone == null ? trimmed : phone;
    }

    /** The digits of a jid, for somebody nothing can name. */
    public static String digitsOf(String jid) {
        if (jid == null) {
            return "";
        }
        int at = jid.indexOf('@');
        return at > 0 ? jid.substring(0, at) : jid;
    }

    /** An unmodifiable empty member map, for a group that could not be read. */
    public static Map<String, String> noMembers() {
        return Collections.emptyMap();
    }
}
