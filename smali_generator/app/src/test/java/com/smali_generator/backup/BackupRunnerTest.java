package com.smali_generator.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;

/**
 * A fresh install has no backup at all, and a future default may write crypt15
 * where this writes crypt14. Neither may throw: the job's contract is to skip
 * and try again tomorrow.
 */
public class BackupRunnerTest {

    private static File dirWith(String... names) throws Exception {
        File dir = File.createTempFile("dbs", "");
        assertTrue(dir.delete() && dir.mkdirs());
        dir.deleteOnExit();
        long stamp = 1_000_000_000L;
        for (String name : names) {
            File f = new File(dir, name);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(name.getBytes("UTF-8"));
            }
            // Ascending, so the last name listed is the newest.
            assertTrue(f.setLastModified(stamp += 60_000L));
            f.deleteOnExit();
        }
        return dir;
    }

    @Test
    public void the_newest_backup_is_chosen_by_modification_time() throws Exception {
        File dir = dirWith("msgstore-2026-09-28.1.db.crypt14", "msgstore.db.crypt14");
        assertEquals("msgstore.db.crypt14", BackupRunner.newestBackup(dir).getName());
    }

    @Test
    public void a_directory_with_no_backup_yields_null() throws Exception {
        assertNull(BackupRunner.newestBackup(dirWith()));
        assertNull(BackupRunner.newestBackup(dirWith("chatsettingsbackup.db.crypt1", "notes.txt")));
    }

    @Test
    public void a_missing_directory_yields_null_rather_than_throwing() {
        assertNull(BackupRunner.newestBackup(new File("/no/such/directory/at/all")));
        assertNull(BackupRunner.newestBackup(null));
    }

    @Test
    public void a_crypt15_only_directory_is_still_a_backup() throws Exception {
        // crypt15 is WhatsApp's end-to-end-encrypted local format. It uploads
        // and restores the same way; only an offline reader cares.
        File dir = dirWith("msgstore.db.crypt15");
        assertEquals("msgstore.db.crypt15", BackupRunner.newestBackup(dir).getName());
    }

    @Test
    public void the_crypt_variant_is_read_off_the_name() {
        assertEquals("crypt14", BackupRunner.cryptOf("msgstore.db.crypt14"));
        assertEquals("crypt15", BackupRunner.cryptOf("msgstore.db.crypt15"));
        assertNull(BackupRunner.cryptOf("msgstore.db"));
        assertNull(BackupRunner.cryptOf(null));
    }

    @Test
    public void run_ids_sort_chronologically_as_plain_strings() {
        // Retention sorts run ids lexicographically, so the stamp must be
        // zero-padded and UTC or the wrong run gets deleted.
        String early = BackupRunner.runId(1_000_000_000_000L);
        String late = BackupRunner.runId(1_900_000_000_000L);
        assertTrue(early.compareTo(late) < 0);
        assertEquals(early.length(), late.length());
    }
}
