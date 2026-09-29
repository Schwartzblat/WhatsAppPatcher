package com.smali_generator.patches;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A backup that quietly lost half its purpose reads exactly like a working one
 * unless the summary says otherwise, and "silently stopped months ago" is the
 * failure that actually costs the user something.
 */
public class DriveBackupTest {

    @Test
    public void an_unconnected_backup_says_so_rather_than_looking_ready() {
        assertEquals("Not connected to Google Drive",
                DriveBackup.summary(false, false, null));
        assertEquals("Not connected to Google Drive",
                DriveBackup.summary(false, true, "Backed up msgstore.db.crypt14 with its key"));
    }

    @Test
    public void a_connected_backup_without_a_passphrase_names_the_missing_half() {
        String summary = DriveBackup.summary(true, false, null);
        assertTrue(summary, summary.toLowerCase().contains("no passphrase"));
    }

    @Test
    public void a_connected_backup_that_has_never_run_says_that() {
        assertTrue(DriveBackup.summary(true, true, null).toLowerCase().contains("not run"));
        assertTrue(DriveBackup.summary(true, true, "").toLowerCase().contains("not run"));
    }

    @Test
    public void the_last_result_is_shown_verbatim_once_there_is_one() {
        assertEquals("Backed up msgstore.db.crypt14 with its key",
                DriveBackup.summary(true, true, "Backed up msgstore.db.crypt14 with its key"));
    }

    @Test
    public void the_hook_id_is_the_one_that_shipped() {
        // Hook.id() is a database key: renaming it silently resets the hook to
        // its default and loses whatever the user had set.
        assertEquals("drive_backup", new DriveBackup().id());
    }
}
