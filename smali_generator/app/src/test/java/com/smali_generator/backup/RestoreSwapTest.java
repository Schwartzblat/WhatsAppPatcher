package com.smali_generator.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assume.assumeFalse;

/**
 * This is the only code that moves the live msgstore.db, and it runs while the
 * app is starting. Every failure here has to leave the original database in
 * place and reachable, because the alternative is somebody's chat history.
 */
public class RestoreSwapTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File file(String name, String content) throws Exception {
        File f = new File(tmp.getRoot(), name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes());
        }
        return f;
    }

    private static String read(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int r = 0;
            while (r < b.length) {
                int n = in.read(b, r, b.length - r);
                if (n < 0) {
                    break;
                }
                r += n;
            }
        }
        return new String(b);
    }

    @Test
    public void the_staged_file_becomes_the_database_and_the_old_one_is_kept() throws Exception {
        File target = file("msgstore.db", "OLD");
        File staged = file("msgstore.db.restoring", "NEW");
        RestoreSwap.Result r = RestoreSwap.swap(staged, target);
        assertTrue(r.message, r.ok);
        assertEquals("NEW", read(target));
        assertEquals("OLD", read(RestoreSwap.asideFile(target)));
        assertFalse(staged.exists());
    }

    @Test
    public void the_write_ahead_log_is_deleted_or_sqlite_replays_it_into_the_new_database()
            throws Exception {
        // A -wal belonging to the old database, replayed into the restored one,
        // is silent corruption of exactly the data being restored.
        File target = file("msgstore.db", "OLD");
        File wal = file("msgstore.db-wal", "WAL");
        File shm = file("msgstore.db-shm", "SHM");
        File journal = file("msgstore.db-journal", "JRN");
        File staged = file("msgstore.db.restoring", "NEW");
        assertTrue(RestoreSwap.swap(staged, target).ok);
        assertFalse(wal.exists());
        assertFalse(shm.exists());
        assertFalse(journal.exists());
    }

    @Test
    public void a_missing_staged_file_changes_nothing() throws Exception {
        File target = file("msgstore.db", "OLD");
        File aside = file("msgstore.db.pre-restore", "ASIDE");
        RestoreSwap.Result r = RestoreSwap.swap(new File(tmp.getRoot(), "gone.restoring"), target);
        assertFalse(r.ok);
        // The guard's own message: any later path would word it differently.
        assertEquals("There is no staged database to put in place.", r.message);
        assertEquals("OLD", read(target));
        assertEquals("ASIDE", read(aside));
    }

    @Test
    public void an_empty_staged_file_is_refused() throws Exception {
        File target = file("msgstore.db", "OLD");
        File staged = new File(tmp.getRoot(), "msgstore.db.restoring");
        assertTrue(staged.createNewFile());
        assertFalse(RestoreSwap.swap(staged, target).ok);
        assertEquals("OLD", read(target));
    }

    @Test
    public void swapping_with_no_existing_database_still_works() throws Exception {
        File target = new File(tmp.getRoot(), "msgstore.db");
        File staged = file("msgstore.db.restoring", "NEW");
        assertTrue(RestoreSwap.swap(staged, target).ok);
        assertEquals("NEW", read(target));
    }

    @Test
    public void a_second_swap_does_not_overwrite_the_first_way_back() throws Exception {
        File target = file("msgstore.db", "ORIGINAL");
        assertTrue(RestoreSwap.swap(file("msgstore.db.restoring", "FIRST"), target).ok);
        assertTrue(RestoreSwap.swap(file("msgstore.db.restoring", "SECOND"), target).ok);
        // The way back must still reach the database the user started with.
        assertEquals("ORIGINAL", read(RestoreSwap.asideFile(target)));
    }

    @Test
    public void undo_puts_the_kept_database_back() throws Exception {
        File target = file("msgstore.db", "OLD");
        assertTrue(RestoreSwap.swap(file("msgstore.db.restoring", "NEW"), target).ok);
        assertTrue(RestoreSwap.hasUndo(target));
        assertTrue(RestoreSwap.undo(target).ok);
        assertEquals("OLD", read(target));
        assertFalse(RestoreSwap.hasUndo(target));
    }

    @Test
    public void undo_with_nothing_kept_is_refused() throws Exception {
        File target = file("msgstore.db", "OLD");
        assertFalse(RestoreSwap.hasUndo(target));
        assertFalse(RestoreSwap.undo(target).ok);
        assertEquals("OLD", read(target));
    }

    @Test
    public void undo_also_clears_the_write_ahead_log_of_the_restored_database() throws Exception {
        File target = file("msgstore.db", "OLD");
        assertTrue(RestoreSwap.swap(file("msgstore.db.restoring", "NEW"), target).ok);
        File wal = file("msgstore.db-wal", "WAL-OF-RESTORED");
        assertTrue(RestoreSwap.undo(target).ok);
        assertFalse(wal.exists());
    }

    @Test
    public void the_suffixes_are_derived_from_the_target_so_both_sides_agree() {
        File target = new File(tmp.getRoot(), "msgstore.db");
        assertEquals("msgstore.db.pre-restore", RestoreSwap.asideFile(target).getName());
        assertEquals("msgstore.db.restoring", RestoreSwap.stagedFile(target).getName());
    }

    /** A staged file in a directory that cannot be written: renaming it away fails. */
    private File unmovableStaged(String content) throws Exception {
        File dir = new File(tmp.getRoot(), "locked");
        assertTrue(dir.mkdir());
        File f = new File(dir, "msgstore.db.restoring");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes());
        }
        dir.setWritable(false);
        assumeFalse("directory permissions are not enforced here", dir.canWrite());
        return f;
    }

    private void unlock() {
        new File(tmp.getRoot(), "locked").setWritable(true);
    }

    @Test
    public void a_failed_second_swap_keeps_the_live_database_and_the_way_back() throws Exception {
        File target = file("msgstore.db", "ORIGINAL");
        assertTrue(RestoreSwap.swap(file("msgstore.db.restoring", "FIRST"), target).ok);
        File staged = unmovableStaged("SECOND");
        try {
            RestoreSwap.Result r = RestoreSwap.swap(staged, target);
            assertFalse(r.ok);
            assertTrue(r.message, r.message.contains("back where it was"));
            assertEquals("FIRST", read(target));
            assertEquals("ORIGINAL", read(RestoreSwap.asideFile(target)));
        } finally {
            unlock();
        }
    }

    @Test
    public void a_failed_first_swap_puts_the_displaced_database_back() throws Exception {
        File target = file("msgstore.db", "OLD");
        File staged = unmovableStaged("NEW");
        try {
            assertFalse(RestoreSwap.swap(staged, target).ok);
            assertEquals("OLD", read(target));
            assertFalse(RestoreSwap.asideFile(target).exists());
        } finally {
            unlock();
        }
    }

    @Test
    public void a_sidecar_that_cannot_be_removed_fails_the_swap() throws Exception {
        File target = file("msgstore.db", "OLD");
        assertTrue(RestoreSwap.swap(file("msgstore.db.restoring", "FIRST"), target).ok);
        // A second swap deletes rather than keeps the sidecars; a non-empty
        // directory cannot be deleted.
        File wal = new File(tmp.getRoot(), "msgstore.db-wal");
        assertTrue(wal.mkdir());
        file("msgstore.db-wal/x", "x");
        RestoreSwap.Result r = RestoreSwap.swap(file("msgstore.db.restoring", "SECOND"), target);
        assertFalse(r.ok);
        assertTrue(r.message, r.message.contains("msgstore.db-wal"));
    }

    @Test
    public void a_failed_undo_leaves_a_database_at_the_target() throws Exception {
        File target = file("msgstore.db", "OLD");
        assertTrue(RestoreSwap.swap(file("msgstore.db.restoring", "NEW"), target).ok);
        // A non-empty directory where the scratch file goes blocks moving the
        // restored database aside.
        File scratch = new File(tmp.getRoot(), "msgstore.db.replaced");
        assertTrue(scratch.mkdir());
        new FileOutputStream(new File(scratch, "x")).close();
        assertFalse(RestoreSwap.undo(target).ok);
        assertEquals("NEW", read(target));
        assertEquals("OLD", read(RestoreSwap.asideFile(target)));
    }

    @Test
    public void a_swap_after_a_swap_keeps_the_original_and_installs_the_newest() throws Exception {
        File target = file("msgstore.db", "ORIGINAL");
        assertTrue(RestoreSwap.swap(file("msgstore.db.restoring", "FIRST"), target).ok);
        assertTrue(RestoreSwap.swap(file("msgstore.db.restoring", "SECOND"), target).ok);
        assertEquals("SECOND", read(target));
        assertEquals("ORIGINAL", read(RestoreSwap.asideFile(target)));
        assertFalse(new File(tmp.getRoot(), "msgstore.db.replaced").exists());
    }

    @Test
    public void the_way_back_carries_the_old_write_ahead_log_and_undo_restores_it() throws Exception {
        File target = file("msgstore.db", "OLD");
        file("msgstore.db-wal", "OLDWAL");
        assertTrue(RestoreSwap.swap(file("msgstore.db.restoring", "NEW"), target).ok);
        assertFalse(new File(tmp.getRoot(), "msgstore.db-wal").exists());
        assertEquals("OLDWAL", read(new File(tmp.getRoot(), "msgstore.db.pre-restore-wal")));
        assertTrue(RestoreSwap.undo(target).ok);
        assertEquals("OLDWAL", read(new File(tmp.getRoot(), "msgstore.db-wal")));
    }

    @Test
    public void a_database_stranded_by_an_interrupted_swap_is_recovered_not_deleted() throws Exception {
        File target = new File(tmp.getRoot(), "msgstore.db");
        file("msgstore.db.replaced", "LIVE");
        assertTrue(RestoreSwap.swap(file("msgstore.db.restoring", "NEW"), target).ok);
        assertEquals("NEW", read(target));
        assertEquals("LIVE", read(RestoreSwap.asideFile(target)));
    }

    @Test
    public void staging_the_live_file_over_itself_is_refused() throws Exception {
        File target = file("msgstore.db", "OLD");
        RestoreSwap.Result r = RestoreSwap.swap(target, target);
        assertFalse(r.ok);
        // Without the guard the rollback also leaves OLD in place, so name the guard.
        assertTrue(r.message, r.message.contains("different files"));
        assertEquals("OLD", read(target));
    }
}
