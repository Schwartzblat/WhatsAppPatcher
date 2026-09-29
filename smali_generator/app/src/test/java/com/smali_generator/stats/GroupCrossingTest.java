package com.smali_generator.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The rule that decides whether two groups hold the same person.
 *
 * Worth its own test because the way this fails is invisible: WhatsApp stores
 * a member as a LID in one group and as a phone jid in another -- its own
 * membership query passes both forms for one user -- so an intersection taken
 * on the stored strings comes back empty and looks exactly like "you have
 * nobody in common".
 */
public class GroupCrossingTest {

    /** Stands in for LidJids, which needs a database and a device. */
    private static final GroupCrossing.Jids JIDS = new GroupCrossing.Jids() {
        private final Map<String, String> phones = phoneByLid();

        @Override
        public String phoneJid(String raw) {
            String phone = phones.get(raw);
            return phone == null ? raw : phone;
        }
    };

    private static Map<String, String> phoneByLid() {
        Map<String, String> phones = new HashMap<>();
        phones.put("111@lid", "972501111111@s.whatsapp.net");
        phones.put("222@lid", "972502222222@s.whatsapp.net");
        // The owner's own two forms, as jid_map pairs them.
        phones.put("999@lid", "972500000000@s.whatsapp.net");
        return phones;
    }

    @Test
    public void only_the_people_in_both_groups_survive() {
        Map<String, String> family = members("a@s.whatsapp.net", "b@s.whatsapp.net", "c@s.whatsapp.net");
        Map<String, String> work = members("b@s.whatsapp.net", "c@s.whatsapp.net", "d@s.whatsapp.net");

        assertEquals(Arrays.asList("b@s.whatsapp.net", "c@s.whatsapp.net"),
                GroupCrossing.shared(Arrays.asList(family, work)));
    }

    @Test
    public void a_third_group_narrows_it_further() {
        Map<String, String> family = members("a@s.whatsapp.net", "b@s.whatsapp.net", "c@s.whatsapp.net");
        Map<String, String> work = members("b@s.whatsapp.net", "c@s.whatsapp.net");
        Map<String, String> gym = members("c@s.whatsapp.net", "d@s.whatsapp.net");

        assertEquals(Collections.singletonList("c@s.whatsapp.net"),
                GroupCrossing.shared(Arrays.asList(family, work, gym)));
    }

    @Test
    public void a_lid_in_one_group_and_a_number_in_another_is_one_person() {
        Map<String, String> family = members("111@lid", "333@s.whatsapp.net");
        Map<String, String> work = members("972501111111@s.whatsapp.net", "444@s.whatsapp.net");

        assertEquals(Collections.singletonList("111@lid"),
                GroupCrossing.shared(Arrays.asList(family, work)));
    }

    @Test
    public void a_survivor_is_named_as_the_first_group_writes_them() {
        // The first group is the one the screen was opened from, and its form
        // is what the name and photo lookups are keyed on: lid_display_name
        // answers for a LID and nothing else.
        Map<String, String> anchor = members("222@lid");
        Map<String, String> other = members("972502222222@s.whatsapp.net");

        assertEquals(Collections.singletonList("222@lid"),
                GroupCrossing.shared(Arrays.asList(anchor, other)));
        assertEquals(Collections.singletonList("972502222222@s.whatsapp.net"),
                GroupCrossing.shared(Arrays.asList(other, anchor)));
    }

    @Test
    public void one_group_is_its_own_members() {
        Map<String, String> family = members("a@s.whatsapp.net", "b@s.whatsapp.net");

        assertEquals(Arrays.asList("a@s.whatsapp.net", "b@s.whatsapp.net"),
                GroupCrossing.shared(Collections.singletonList(family)));
    }

    @Test
    public void no_groups_means_nobody() {
        // Not "everybody": an empty intersection of nothing is the empty
        // screen the user sees before picking a group, and answering with the
        // whole contact list there would be nonsense.
        assertTrue(GroupCrossing.shared(Collections.<Map<String, String>>emptyList()).isEmpty());
    }

    @Test
    public void the_owner_is_never_a_shared_member() {
        // You are in every group you can pick, so you would survive every
        // intersection and be the one row that means nothing.
        Set<String> self = GroupCrossing.self("972500000000@s.whatsapp.net", "999@lid", JIDS);
        Map<String, String> family = GroupCrossing.members(
                Arrays.asList("999@lid", "a@s.whatsapp.net"), JIDS, self);
        Map<String, String> work = GroupCrossing.members(
                Arrays.asList("972500000000@s.whatsapp.net", "a@s.whatsapp.net"), JIDS, self);

        assertEquals(Collections.singletonList("a@s.whatsapp.net"),
                GroupCrossing.shared(Arrays.asList(family, work)));
    }

    @Test
    public void the_owner_is_recognised_by_either_of_the_two_forms_registered() {
        Set<String> self = GroupCrossing.self("972500000000@s.whatsapp.net", "999@lid", JIDS);

        // Both spellings collapse onto the same phone jid, so one entry.
        assertEquals(Collections.singleton("972500000000@s.whatsapp.net"), self);
    }

    @Test
    public void an_owner_lid_the_jid_map_has_never_seen_is_still_the_owner() {
        // jid_map is WhatsApp's, not ours, and it does not have to carry the
        // owner's own pair. Dropping the untranslatable form would put "You"
        // back in the result of every group that stores members as LIDs.
        Set<String> self = GroupCrossing.self("972500000000@s.whatsapp.net", "777@lid", JIDS);
        Map<String, String> family = GroupCrossing.members(
                Arrays.asList("777@lid", "a@s.whatsapp.net"), JIDS, self);

        assertEquals(Collections.singletonList("a@s.whatsapp.net"),
                GroupCrossing.shared(Collections.singletonList(family)));
    }

    @Test
    public void an_unregistered_device_has_no_owner_jid_to_exclude() {
        assertTrue(GroupCrossing.self(null, null, JIDS).isEmpty());
        assertTrue(GroupCrossing.self("", "  ", JIDS).isEmpty());
    }

    @Test
    public void a_member_row_with_no_jid_is_not_a_person() {
        // An orphaned foreign key leaves an empty raw_string, and WhatsApp's
        // own membership rows carry one too; counted as somebody they would
        // be a nameless row present in every group.
        Map<String, String> family = GroupCrossing.members(
                Arrays.asList("", null, "   ", "nonsense", "a@s.whatsapp.net"),
                JIDS, Collections.<String>emptySet());

        assertEquals(Collections.singletonList("a@s.whatsapp.net"),
                GroupCrossing.shared(Collections.singletonList(family)));
    }

    @Test
    public void a_member_listed_twice_is_counted_once() {
        // wa.db's duplication has a counterpart here: the same person can
        // arrive under both of their forms in one group.
        Map<String, String> family = GroupCrossing.members(
                Arrays.asList("111@lid", "972501111111@s.whatsapp.net"),
                JIDS, Collections.<String>emptySet());

        assertEquals(1, family.size());
        assertEquals(Collections.singletonList("111@lid"),
                GroupCrossing.shared(Collections.singletonList(family)));
    }

    private static Map<String, String> members(String... rawJids) {
        return GroupCrossing.members(new LinkedHashSet<>(Arrays.asList(rawJids)), JIDS,
                Collections.<String>emptySet());
    }
}
