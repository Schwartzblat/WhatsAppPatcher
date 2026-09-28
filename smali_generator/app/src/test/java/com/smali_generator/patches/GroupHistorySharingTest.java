package com.smali_generator.patches;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The four numbers this hook writes are read out of the app, and two of them are
 * not the number the screen shows: the sender's window is seconds where the
 * screen says days, and the receiver's is *half* the window it compares against.
 *
 * 1209600 is what 2.26.37.74's default tables hold for both 18406 and 21313, and
 * those two constants are what a row falls back to when this account has not been
 * seen to read the property -- so a conversion that drifted from them would put a
 * wrong number under "ships as" and nothing would say so.
 */
public class GroupHistorySharingTest {

    @Test
    public void the_shipped_labels_match_the_properties_they_stand_for() {
        assertEquals(1209600, GroupHistorySharing.sendWindowSeconds(
                GroupHistorySharing.SHIPPED_SEND_DAYS));
        assertEquals(1209600, GroupHistorySharing.acceptHalfWindowSeconds(
                GroupHistorySharing.SHIPPED_ACCEPT_DAYS));
    }

    /**
     * The receive side is offered in days and stored halved, so a choice that is
     * not a whole number of half-days comes back as something else -- and the row
     * would then disagree with what the app was told.
     */
    @Test
    public void every_accept_choice_survives_the_halving() {
        for (int days : GroupHistorySharing.ACCEPT_DAY_CHOICES) {
            if (days == GroupHistorySharing.LEAVE_ALONE) {
                continue;
            }
            assertEquals(days, GroupHistorySharing.acceptDaysFor(
                    GroupHistorySharing.acceptHalfWindowSeconds(days)));
        }
    }

    /**
     * Leaving the app alone has to be a choice on every one of the four, and the
     * first: once something else has been picked there is otherwise no way back to
     * the value the server is handing this account, which can be wider than
     * anything on the list.
     */
    @Test
    public void every_setting_can_be_put_back_to_the_apps_own() {
        assertEquals(GroupHistorySharing.LEAVE_ALONE, GroupHistorySharing.SEND_MAX_CHOICES[0]);
        assertEquals(GroupHistorySharing.LEAVE_ALONE, GroupHistorySharing.SEND_DAY_CHOICES[0]);
        assertEquals(GroupHistorySharing.LEAVE_ALONE, GroupHistorySharing.ACCEPT_MAX_CHOICES[0]);
        assertEquals(GroupHistorySharing.LEAVE_ALONE, GroupHistorySharing.ACCEPT_DAY_CHOICES[0]);
    }
}
