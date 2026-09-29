package com.smali_generator.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Deleting the wrong run is the one irreversible thing this feature does, so
 * the arithmetic is separated from everything that talks to Drive.
 */
public class RetentionTest {

    @Test
    public void nothing_is_deleted_while_under_the_limit() {
        assertTrue(Retention.toDelete(Arrays.asList("2026-01-01", "2026-01-02"), 7).isEmpty());
    }

    @Test
    public void nothing_is_deleted_at_exactly_the_limit() {
        assertTrue(Retention.toDelete(Arrays.asList("a", "b", "c"), 3).isEmpty());
    }

    @Test
    public void the_oldest_go_first_and_the_newest_survive() {
        List<String> runs = Arrays.asList("2026-01-03", "2026-01-01", "2026-01-05", "2026-01-02");
        assertEquals(Arrays.asList("2026-01-01", "2026-01-02"), Retention.toDelete(runs, 2));
    }

    @Test
    public void an_unsorted_input_is_sorted_before_deciding() {
        // Drive returns files in whatever order it likes; run ids sort
        // lexicographically because they are zero-padded UTC timestamps.
        List<String> runs = Arrays.asList("2026-01-10T000000Z", "2026-01-02T000000Z");
        assertEquals(Collections.singletonList("2026-01-02T000000Z"), Retention.toDelete(runs, 1));
    }

    @Test
    public void a_keep_of_zero_or_less_deletes_nothing() {
        // A misconfigured count must never be read as "delete everything".
        assertTrue(Retention.toDelete(Arrays.asList("a", "b"), 0).isEmpty());
        assertTrue(Retention.toDelete(Arrays.asList("a", "b"), -3).isEmpty());
    }

    @Test
    public void an_empty_or_null_list_is_handled() {
        assertTrue(Retention.toDelete(Collections.emptyList(), 7).isEmpty());
        assertTrue(Retention.toDelete(null, 7).isEmpty());
    }
}
