package com.smali_generator.backup;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Changing the passphrase does not re-wrap the runs already in Drive. A screen
 * that still calls them readable is telling the user they have an archive they
 * do not have, which they find out only when they need it.
 */
public class RestoreRunnerTest {

    @Test
    public void a_run_with_a_key_and_a_known_passphrase_is_readable() {
        assertEquals(RestoreRunner.Readable.YES, RestoreRunner.readability(true, true));
    }

    @Test
    public void a_run_with_no_key_is_never_readable() {
        assertEquals(RestoreRunner.Readable.NO_KEY, RestoreRunner.readability(false, true));
        assertEquals(RestoreRunner.Readable.NO_KEY, RestoreRunner.readability(false, false));
    }

    @Test
    public void a_run_with_a_key_but_no_passphrase_on_file_is_not_claimed_readable() {
        assertEquals(RestoreRunner.Readable.UNKNOWN_PASSPHRASE,
                RestoreRunner.readability(true, false));
    }
}
