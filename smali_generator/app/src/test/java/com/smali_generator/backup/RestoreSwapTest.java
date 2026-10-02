package com.smali_generator.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

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
        // Review Focus 5: a -wal belonging to the old database, replayed into the
        // restored one, is silent corruption of exactly the data being restored.
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
        RestoreSwap.Result r = RestoreSwap.swap(new File(tmp.getRoot(), "gone.restoring"), target);
        assertFalse(r.ok);
        assertEquals("OLD", read(target));
        assertFalse(RestoreSwap.asideFile(target).exists());
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
}
