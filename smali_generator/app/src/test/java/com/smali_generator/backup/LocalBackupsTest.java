package com.smali_generator.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * The restore screen offers whatever this returns, so a stray file listed here
 * is a file somebody can ask to have written over their chat history.
 */
public class LocalBackupsTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File write(String name, int size, long modified) throws Exception {
        File f = new File(tmp.getRoot(), name);
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[size]);
        }
        assertTrue(f.setLastModified(modified));
        return f;
    }

    @Test
    public void only_crypt14_files_are_listed() throws Exception {
        write("msgstore.db.crypt14", 10, 5000);
        write("msgstore-2026-10-01.1.db.crypt14", 10, 4000);
        write("notes.txt", 10, 6000);
        write("msgstore.db.crypt15", 10, 7000);
        List<LocalBackups.Entry> found = LocalBackups.list(tmp.getRoot());
        assertEquals(2, found.size());
        for (LocalBackups.Entry e : found) {
            assertTrue(e.name.endsWith(".crypt14"));
        }
    }

    @Test
    public void the_newest_comes_first() throws Exception {
        write("msgstore-old.db.crypt14", 10, 1000);
        write("msgstore.db.crypt14", 10, 9000);
        List<LocalBackups.Entry> found = LocalBackups.list(tmp.getRoot());
        assertEquals("msgstore.db.crypt14", found.get(0).name);
    }

    @Test
    public void sizes_and_times_are_reported() throws Exception {
        write("msgstore.db.crypt14", 1234, 5000);
        LocalBackups.Entry e = LocalBackups.list(tmp.getRoot()).get(0);
        assertEquals(1234, e.bytes);
        assertEquals(5000, e.modifiedAtMs);
    }

    @Test
    public void an_empty_file_is_not_offered() throws Exception {
        write("msgstore.db.crypt14", 0, 5000);
        assertTrue(LocalBackups.list(tmp.getRoot()).isEmpty());
    }

    @Test
    public void a_directory_named_like_a_backup_is_not_offered() {
        assertTrue(new File(tmp.getRoot(), "msgstore.db.crypt14").mkdirs());
        assertTrue(LocalBackups.list(tmp.getRoot()).isEmpty());
    }

    @Test
    public void a_missing_directory_yields_an_empty_list_rather_than_a_throw() {
        assertTrue(LocalBackups.list(new File(tmp.getRoot(), "nope")).isEmpty());
        assertTrue(LocalBackups.list(null).isEmpty());
    }

    @Test
    public void crypt15_and_other_extensions_are_rejected_by_name() {
        assertTrue(LocalBackups.isCrypt14("msgstore.db.crypt14"));
        assertFalse(LocalBackups.isCrypt14("msgstore.db.crypt15"));
        assertFalse(LocalBackups.isCrypt14("msgstore.db.crypt14.tmp"));
        assertFalse(LocalBackups.isCrypt14(null));
    }

    @Test
    public void whatsapps_other_crypt14_files_are_not_offered_as_chat_backups() throws Exception {
        // Measured on the test device: WhatsApp writes all of these. wa.db.crypt14 is
        // the contacts database -- genuine SQLite, so it would pass the magic-byte and
        // integrity checks downstream and be written over the user's chats.
        write("wa.db.crypt14", 10, 5000);
        write("chatsettingsbackup.db.crypt14", 10, 5000);
        write("commerce_backup.db.crypt14", 10, 5000);
        write("status_backup.db.crypt14", 10, 5000);
        write("stickers_db.bak.crypt14", 10, 5000);
        write("offloaded-media.db.crypt14", 10, 5000);
        write("backup_settings.json.crypt14", 10, 5000);
        write("msgstore.db.crypt14", 10, 6000);
        List<LocalBackups.Entry> found = LocalBackups.list(tmp.getRoot());
        assertEquals(1, found.size());
        assertEquals("msgstore.db.crypt14", found.get(0).name);
    }
}
