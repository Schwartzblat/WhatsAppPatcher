package com.smali_generator.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Changing the passphrase does not re-wrap the runs already in Drive. A screen
 * that still calls them readable is telling the user they have an archive they
 * do not have, which they find out only when they need it.
 */
public class RestoreRunnerTest {

    @Test
    public void a_run_wrapped_under_this_devices_passphrase_is_readable() {
        assertEquals(RestoreRunner.Readable.YES, RestoreRunner.readability(true, "v1", "v1"));
    }

    @Test
    public void a_run_with_no_key_is_never_readable() {
        assertEquals(RestoreRunner.Readable.NO_KEY, RestoreRunner.readability(false, "v1", "v1"));
        assertEquals(RestoreRunner.Readable.NO_KEY, RestoreRunner.readability(false, null, null));
    }

    @Test
    public void a_run_wrapped_under_a_different_passphrase_is_not_claimed_readable() {
        // The passphrase was changed after this run was made. The blob still
        // holds the old one, and nothing on the device can open it.
        assertEquals(RestoreRunner.Readable.UNKNOWN_PASSPHRASE,
                RestoreRunner.readability(true, "old", "new"));
    }

    @Test
    public void a_device_with_no_passphrase_at_all_is_not_claimed_readable() {
        assertEquals(RestoreRunner.Readable.UNKNOWN_PASSPHRASE,
                RestoreRunner.readability(true, "v1", null));
    }

    @Test
    public void a_run_that_does_not_say_which_passphrase_it_used_is_not_claimed_readable() {
        // Manifests written before the verifier was recorded. Unknown is not
        // yes: the screen may not promise what it cannot check.
        assertEquals(RestoreRunner.Readable.UNKNOWN_PASSPHRASE,
                RestoreRunner.readability(true, null, "v1"));
    }

    @Test
    public void a_name_from_drive_may_not_escape_the_backup_directory() {
        // The name comes from Drive's listing, which the user can rename. It
        // is joined onto a shared-storage path, so a separator in it would
        // write somewhere this feature has no business writing.
        assertEquals("msgstore.db.crypt14", RestoreRunner.safeName("msgstore.db.crypt14"));
        assertNull(RestoreRunner.safeName("../../../data/local/tmp/x.crypt14"));
        assertNull(RestoreRunner.safeName("sub/msgstore.db.crypt14"));
        assertNull(RestoreRunner.safeName(".."));
        assertNull(RestoreRunner.safeName(""));
        assertNull(RestoreRunner.safeName(null));
    }
}
