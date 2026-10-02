package com.smali_generator.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The decisions are split out from the file moves and the database because they
 * run during app startup, where the cost of being wrong is the app not
 * starting -- and because PatchDb and Context cannot be reached from a unit
 * test in this project.
 */
public class PendingRestoreTest {

    private static final PendingRestore.Action NOTHING = PendingRestore.Action.NOTHING;

    @Test
    public void a_recorded_swap_with_its_file_present_proceeds() {
        PendingRestore.Decision d = PendingRestore.decide(
                "/data/data/com.whatsapp/databases/msgstore.db.restoring",
                "/data/data/com.whatsapp/databases/msgstore.db", true, true);
        assertTrue(d.reason, d.shouldSwap);
    }

    @Test
    public void nothing_recorded_means_nothing_to_do() {
        assertFalse(PendingRestore.decide(null, null, false, true).shouldSwap);
        assertFalse(PendingRestore.decide("", "", false, true).shouldSwap);
    }

    @Test
    public void a_recorded_swap_whose_staged_file_vanished_does_not_proceed() {
        // Review Focus 4: a cleared cache or a manual delete between staging and
        // the next start. Startup must carry on, not die and not retry forever.
        PendingRestore.Decision d = PendingRestore.decide("/x/msgstore.db.restoring",
                "/x/msgstore.db", false, true);
        assertFalse(d.shouldSwap);
        assertTrue(d.reason.toLowerCase().contains("gone"));
    }

    @Test
    public void a_target_directory_that_cannot_be_written_does_not_proceed() {
        PendingRestore.Decision d = PendingRestore.decide("/x/msgstore.db.restoring",
                "/x/msgstore.db", true, false);
        assertFalse(d.shouldSwap);
    }

    @Test
    public void a_recorded_pair_that_does_not_belong_together_is_refused() {
        // The staged path must be the target plus the staging suffix, or the
        // record has been tampered with and nothing should move.
        PendingRestore.Decision d = PendingRestore.decide("/x/something-else.restoring",
                "/x/msgstore.db", true, true);
        assertFalse(d.shouldSwap);
    }

    @Test
    public void only_a_matching_pair_may_have_its_staged_file_deleted() {
        assertTrue(PendingRestore.pairBelongs("/x/msgstore.db.restoring", "/x/msgstore.db"));
        assertFalse(PendingRestore.pairBelongs("/x/other", "/x/msgstore.db"));
        assertFalse(PendingRestore.pairBelongs(null, "/x/msgstore.db"));
    }

    // ---- automatic rollback ----

    @Test
    public void rollback_needs_all_three_conditions() {
        assertTrue(PendingRestore.decideRollback("1700000000000", true, true).shouldSwap);
        assertFalse("no marker", PendingRestore.decideRollback("", true, true).shouldSwap);
        assertFalse("null marker", PendingRestore.decideRollback(null, true, true).shouldSwap);
        assertFalse("clean run", PendingRestore.decideRollback("1", false, true).shouldSwap);
        assertFalse("nothing to go back to", PendingRestore.decideRollback("1", true, false).shouldSwap);
    }

    @Test
    public void a_marker_that_is_not_a_number_still_counts_as_probation() {
        assertTrue(PendingRestore.decideRollback("garbage", true, true).shouldSwap);
    }

    @Test
    public void probation_ends_on_a_clean_launch_or_when_there_is_nothing_to_roll_back_to() {
        assertTrue(PendingRestore.probationOver("1", false, true));
        assertTrue(PendingRestore.probationOver("1", true, false));
        assertFalse("bad run with a way back keeps it", PendingRestore.probationOver("1", true, true));
        assertFalse("no marker, nothing to end", PendingRestore.probationOver("", false, true));
    }

    // ---- which single action startup takes ----

    @Test
    public void a_rollback_outranks_every_request() {
        assertEquals(PendingRestore.Action.ROLLBACK,
                PendingRestore.plan(false, true, "1", true, true, true));
        assertEquals(PendingRestore.Action.ROLLBACK,
                PendingRestore.plan(false, true, "1", true, false, true));
        assertEquals(PendingRestore.Action.ROLLBACK,
                PendingRestore.plan(false, true, "1", true, true, false));
    }

    @Test
    public void safe_mode_still_permits_the_rollback() {
        assertEquals(PendingRestore.Action.ROLLBACK,
                PendingRestore.plan(true, true, "1", true, false, false));
    }

    @Test
    public void safe_mode_stands_aside_from_both_requests() {
        assertEquals(PendingRestore.Action.STAND_ASIDE,
                PendingRestore.plan(true, true, "", false, false, true));
        assertEquals(PendingRestore.Action.STAND_ASIDE,
                PendingRestore.plan(true, true, "", false, true, false));
    }

    @Test
    public void both_requests_at_once_are_refused() {
        assertEquals(PendingRestore.Action.REFUSE_CONFLICT,
                PendingRestore.plan(false, false, "", false, true, true));
    }

    @Test
    public void a_single_request_is_carried_out() {
        assertEquals(PendingRestore.Action.UNDO,
                PendingRestore.plan(false, false, "", false, true, false));
        assertEquals(PendingRestore.Action.SWAP,
                PendingRestore.plan(false, false, "", false, false, true));
        // One bad exit is common and says nothing about this.
        assertEquals(PendingRestore.Action.SWAP,
                PendingRestore.plan(false, true, "", false, false, true));
    }

    @Test
    public void with_nothing_recorded_nothing_happens() {
        assertEquals(NOTHING, PendingRestore.plan(false, false, "", false, false, false));
        assertEquals(NOTHING, PendingRestore.plan(true, true, "", false, false, false));
        // A live probation on a clean run is not a reason to move anything.
        assertEquals(NOTHING, PendingRestore.plan(false, false, "1", true, false, false));
    }
}
