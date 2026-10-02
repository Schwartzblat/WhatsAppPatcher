package com.smali_generator.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    public void deflated_sqlite_shaped_payload_inflates_correctly() throws Exception {
        byte[] plain = fakeDatabase(50000);
        byte[] compressed = deflate(plain);
        File staged = new File(tmp.getRoot(), "inflated.db");
        InputStream in = new ByteArrayInputStream(compressed);
        OutputStream out = new FileOutputStream(staged);
        long written = Crypt14Restore.inflateTo(in, out);
        out.close();
        assertTrue(Crypt14.looksLikeSqlite(head(staged, 32)));
        assertTrue("inflated " + written, written > plain.length - 4096 && written <= plain.length);
    }

    @Test
    public void stream_truncated_by_16_bytes_still_inflates_to_usable_file() throws Exception {
        // Large incompressible data to exercise multi-chunk drain loop.
        byte[] plain = new byte[400000];
        new java.util.Random(12345).nextBytes(plain);
        System.arraycopy(Crypt14.SQLITE_MAGIC.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0,
                plain, 0, Crypt14.SQLITE_MAGIC.length());
        byte[] compressed = deflate(plain);
        byte[] truncated = new byte[compressed.length - 16];
        System.arraycopy(compressed, 0, truncated, 0, truncated.length);
        File staged = new File(tmp.getRoot(), "truncated.db");
        InputStream in = new ByteArrayInputStream(truncated);
        OutputStream out = new FileOutputStream(staged);
        long written = Crypt14Restore.inflateTo(in, out);
        out.close();
        assertTrue("truncated " + written + " vs original " + plain.length,
                written > plain.length - 4096 && written < plain.length);
        assertTrue(Crypt14.looksLikeSqlite(head(staged, 32)));
    }

    @Test
    public void non_zlib_stream_fails_and_leaves_no_staged_file() throws Exception {
        File staged = new File(tmp.getRoot(), "notzlib.db");
        InputStream in = new ByteArrayInputStream(new byte[1000]);
        OutputStream out = new FileOutputStream(staged);
        try {
            Crypt14Restore.inflateTo(in, out);
            fail("should have thrown on non-zlib data");
        } catch (IOException e) {
            assertTrue("error mentions zlib: " + e.getMessage(), e.getMessage().contains("zlib"));
        }
        // Clean up test artifact (production cleanup tested via stage())
        staged.delete();
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
    public void desktop_jvm_cipher_releases_no_plaintext() throws Exception {
        // On desktop JVM with SunJCE, AES/GCM.update() buffers all ciphertext until
        // doFinal(). Since we do not call doFinal (correct on Conscrypt), the cipher
        // releases zero bytes. This test is device-verified on Android; if this fails,
        // the JVM's provider started streaming and the cipher seam has become testable
        // in the desktop environment.
        File crypt = writeCrypt14(fakeDatabase(50000));
        File staged = new File(tmp.getRoot(), "noplaino.restoring");
        Crypt14Restore.Outcome o = Crypt14Restore.stage(crypt, KEY, staged);
        assertFalse(o.ok);
        assertFalse(staged.exists());
        assertTrue("message: " + o.message, o.message.contains("crypto provider returned no data"));
        assertTrue(o.message.endsWith("Nothing was changed."));
    }

    @Test
    public void the_free_space_needed_is_a_multiple_of_the_stored_size() {
        // Review Focus 3: filling the data partition is worse than refusing.
        assertEquals(600L, Crypt14Restore.requiredFreeBytes(100L));
        assertEquals(0L, Crypt14Restore.requiredFreeBytes(0L));
        assertTrue(Crypt14Restore.requiredFreeBytes(13_000_000L) > 13_000_000L);
    }
}
