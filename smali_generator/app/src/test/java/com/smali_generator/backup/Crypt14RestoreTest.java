package com.smali_generator.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.zip.Deflater;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The fixtures here are built in the test, never checked in: a real crypt14
 * needs a real device key, and neither a key nor anybody's messages belong in
 * this repository.
 */
public class Crypt14RestoreTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final byte[] KEY = new byte[32];
    private static final byte[] IV = new byte[16];
    static {
        for (int i = 0; i < 32; i++) {
            KEY[i] = (byte) (i + 1);
        }
        for (int i = 0; i < 16; i++) {
            IV[i] = (byte) (i + 100);
        }
    }

    /** Bytes that start with SQLite's magic, so a staged file can be recognised. */
    private static byte[] fakeDatabase(int size) {
        byte[] db = new byte[size];
        System.arraycopy(Crypt14.SQLITE_MAGIC.getBytes(), 0, db, 0, Crypt14.SQLITE_MAGIC.length());
        for (int i = Crypt14.SQLITE_MAGIC.length(); i < size; i++) {
            db[i] = (byte) (i % 251);
        }
        return db;
    }

    private static byte[] deflate(byte[] plain) {
        Deflater d = new Deflater();
        d.setInput(plain);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) {
            out.write(buf, 0, d.deflate(buf));
        }
        d.end();
        return out.toByteArray();
    }

    /**
     * A crypt14 as WhatsApp writes one: no GCM tag appended, which is why the
     * restore must never call doFinal.
     */
    private File writeCrypt14(byte[] plain) throws Exception {
        byte[] compressed = deflate(plain);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"), new GCMParameterSpec(128, IV));
        byte[] withTag = c.doFinal(compressed);
        // Drop the 16-byte tag: real crypt14 files do not carry one, so a fixture
        // that keeps it would let doFinal succeed here and fail on every real backup.
        byte[] ciphertext = new byte[withTag.length - 16];
        System.arraycopy(withTag, 0, ciphertext, 0, ciphertext.length);

        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.write(0x1a); inner.write(32); inner.write(new byte[32]);
        inner.write(0x22); inner.write(16); inner.write(new byte[16]);
        inner.write(0x2a); inner.write(16); inner.write(IV);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(0x12); header.write(inner.size()); header.write(inner.toByteArray());
        byte[] h = header.toByteArray();

        File f = tmp.newFile("msgstore.db.crypt14." + System.nanoTime());
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(h.length & 0x7f | 0x80);
            out.write(h.length >> 7);
            out.write(h);
            out.write(ciphertext);
        }
        return f;
    }

    private static byte[] head(File f, int n) throws Exception {
        byte[] b = new byte[n];
        try (FileInputStream in = new FileInputStream(f)) {
            int read = 0;
            while (read < n) {
                int r = in.read(b, read, n - read);
                if (r < 0) {
                    break;
                }
                read += r;
            }
        }
        return b;
    }

    @Test
    public void a_backup_decrypts_and_inflates_to_the_staged_file() throws Exception {
        byte[] plain = fakeDatabase(200000);
        File crypt = writeCrypt14(plain);
        File staged = new File(tmp.getRoot(), "msgstore.db.restoring");
        Crypt14Restore.Outcome o = Crypt14Restore.stage(crypt, KEY, staged);
        assertTrue(o.message, o.ok);
        assertTrue(staged.isFile());
        assertTrue(Crypt14.looksLikeSqlite(head(staged, 32)));
        // update()-only withholds a tag's worth of trailing plaintext, so the
        // output is allowed to be slightly short of the original -- never longer.
        assertTrue("staged " + staged.length() + " of " + plain.length,
                staged.length() > plain.length - 4096 && staged.length() <= plain.length);
    }

    @Test
    public void the_wrong_key_is_refused_and_leaves_no_staged_file() throws Exception {
        // Review Focus 2: the key changes when the number is re-registered, so
        // this is the ordinary case of restoring an older backup, not an exotic one.
        File crypt = writeCrypt14(fakeDatabase(50000));
        byte[] wrong = new byte[32];
        File staged = new File(tmp.getRoot(), "wrongkey.restoring");
        Crypt14Restore.Outcome o = Crypt14Restore.stage(crypt, wrong, staged);
        assertFalse(o.ok);
        assertTrue(o.message.toLowerCase().contains("key"));
        assertFalse(staged.exists());
    }

    @Test
    public void a_file_that_is_not_a_crypt14_is_refused() throws Exception {
        File junk = tmp.newFile("junk.crypt14");
        try (FileOutputStream out = new FileOutputStream(junk)) {
            out.write(new byte[64]);
        }
        File staged = new File(tmp.getRoot(), "junk.restoring");
        Crypt14Restore.Outcome o = Crypt14Restore.stage(junk, KEY, staged);
        assertFalse(o.ok);
        assertFalse(staged.exists());
    }

    @Test
    public void a_missing_source_is_refused() {
        Crypt14Restore.Outcome o = Crypt14Restore.stage(
                new File(tmp.getRoot(), "nope.crypt14"), KEY, new File(tmp.getRoot(), "s"));
        assertFalse(o.ok);
    }

    @Test
    public void a_stale_staged_file_is_replaced_not_appended_to() throws Exception {
        File crypt = writeCrypt14(fakeDatabase(50000));
        File staged = new File(tmp.getRoot(), "stale.restoring");
        try (FileOutputStream out = new FileOutputStream(staged)) {
            out.write(new byte[999999]);
        }
        assertTrue(Crypt14Restore.stage(crypt, KEY, staged).ok);
        assertTrue(staged.length() < 999999);
    }

    @Test
    public void the_free_space_needed_is_a_multiple_of_the_stored_size() {
        // Review Focus 3: filling the data partition is worse than refusing.
        assertEquals(600L, Crypt14Restore.requiredFreeBytes(100L));
        assertEquals(0L, Crypt14Restore.requiredFreeBytes(0L));
        assertTrue(Crypt14Restore.requiredFreeBytes(13_000_000L) > 13_000_000L);
    }
}
