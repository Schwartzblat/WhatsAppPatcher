package com.smali_generator.backup;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Getting the offsets wrong does not fail loudly -- it decrypts the wrong bytes
 * with the right key and produces plausible rubbish, so every boundary here is
 * pinned against the layout measured from two real backups on 2026-10-02.
 */
public class Crypt14Test {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** A header shaped exactly like the real thing: field 2 nested, with 2.3=32B, 2.4=16B, 2.5=16B. */
    private static byte[] realisticHeader(byte[] iv) throws Exception {
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.write(0x0a); inner.write(2); inner.write(new byte[]{1, 2});          // 2.1, 2 bytes
        inner.write(0x12); inner.write(1); inner.write(new byte[]{3});             // 2.2, 1 byte
        inner.write(0x1a); inner.write(32); inner.write(new byte[32]);             // 2.3, 32 bytes
        inner.write(0x22); inner.write(16); inner.write(new byte[16]);             // 2.4, 16 bytes
        inner.write(0x2a); inner.write(16); inner.write(iv);                       // 2.5, THE IV
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(0x12); header.write(inner.size()); header.write(inner.toByteArray()); // field 2
        header.write(0x22); header.write(4); header.write(new byte[]{9, 9, 9, 9});  // field 4
        return header.toByteArray();
    }

    private static byte[] crypt14(byte[] header, byte[] ciphertext) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header.length & 0x7f | 0x80);        // 2-byte varint, as the real files use
        out.write(header.length >> 7);
        out.write(header);
        out.write(ciphertext);
        return out.toByteArray();
    }

    @Test
    public void the_iv_is_field_five_of_the_nested_message_at_field_two() throws Exception {
        byte[] iv = new byte[16];
        for (int i = 0; i < 16; i++) {
            iv[i] = (byte) (0xE0 + i);
        }
        byte[] file = crypt14(realisticHeader(iv), new byte[100]);
        Crypt14.Header h = Crypt14.parseHeader(file);
        assertNotNull(h);
        assertArrayEquals(iv, h.iv);
    }

    @Test
    public void the_ciphertext_starts_after_the_prefix_and_the_header() throws Exception {
        byte[] file = crypt14(realisticHeader(new byte[16]), new byte[100]);
        Crypt14.Header h = Crypt14.parseHeader(file);
        assertNotNull(h);
        // The test header is 85 bytes; real files put it at 196 for a 194-byte header.
        assertEquals(87, h.cipherOffset);
    }

    @Test
    public void a_file_too_short_to_hold_a_header_is_refused() {
        assertNull(Crypt14.parseHeader(new byte[10]));
        assertNull(Crypt14.parseHeader(new byte[0]));
    }

    @Test
    public void a_header_with_no_sixteen_byte_field_five_is_refused() throws Exception {
        // A future WhatsApp format change must be refused, not sliced at a guessed offset.
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.write(0x1a); inner.write(32); inner.write(new byte[32]);   // only 2.3
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(0x12); header.write(inner.size()); header.write(inner.toByteArray());
        assertNull(Crypt14.parseHeader(crypt14(header.toByteArray(), new byte[50])));
    }

    @Test
    public void a_truncated_header_is_refused_rather_than_read_past_the_end() throws Exception {
        byte[] file = crypt14(realisticHeader(new byte[16]), new byte[100]);
        byte[] cut = new byte[20];
        System.arraycopy(file, 0, cut, 0, 20);
        assertNull(Crypt14.parseHeader(cut));
    }

    @Test
    public void the_database_key_is_thirty_two_bytes_at_offset_one_hundred_and_twenty_six()
            throws Exception {
        byte[] keyFile = new byte[158];
        for (int i = 0; i < keyFile.length; i++) {
            keyFile[i] = (byte) i;
        }
        File f = tmp.newFile("key");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(keyFile);
        }
        byte[] key = Crypt14.databaseKey(f);
        assertNotNull(key);
        assertEquals(32, key.length);
        assertEquals((byte) 126, key[0]);
        assertEquals((byte) 157, key[31]);
    }

    @Test
    public void a_key_file_too_short_yields_null_rather_than_a_short_key() throws Exception {
        File f = tmp.newFile("short");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[100]);
        }
        assertNull(Crypt14.databaseKey(f));
    }

    @Test
    public void a_missing_key_file_yields_null() {
        assertNull(Crypt14.databaseKey(new File(tmp.getRoot(), "nope")));
    }

    @Test
    public void the_whatsapp_version_is_read_from_field_four_point_one() throws Exception {
        // Measured on two real backups: field 4.1 is the versionName as text.
        ByteArrayOutputStream four = new ByteArrayOutputStream();
        four.write(0x0a); four.write(10); four.write("2.26.38.74".getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.write(0x2a); inner.write(16); inner.write(new byte[16]);
        header.write(0x12); header.write(inner.size()); header.write(inner.toByteArray());
        header.write(0x22); header.write(four.size()); header.write(four.toByteArray());
        Crypt14.Header h = Crypt14.parseHeader(crypt14(header.toByteArray(), new byte[50]));
        assertNotNull(h);
        assertEquals("2.26.38.74", h.waVersion);
    }

    @Test
    public void a_header_without_a_version_still_parses_with_a_null_version() throws Exception {
        Crypt14.Header h = Crypt14.parseHeader(crypt14(realisticHeader(new byte[16]), new byte[50]));
        assertNotNull(h);
        assertNull(h.waVersion);
    }

    @Test
    public void a_backup_from_a_newer_whatsapp_is_refused_and_older_or_equal_allowed() {
        assertTrue(Crypt14.isNotNewerThan("2.26.38.74", "2.26.38.74"));
        assertTrue(Crypt14.isNotNewerThan("2.26.37.74", "2.26.38.74"));
        assertTrue(Crypt14.isNotNewerThan("2.26.38.9", "2.26.38.74"));
        assertFalse(Crypt14.isNotNewerThan("2.26.39.1", "2.26.38.74"));
        assertFalse(Crypt14.isNotNewerThan("2.27.0.0", "2.26.38.74"));
        assertTrue(Crypt14.isNotNewerThan("2.26.38", "2.26.38.74"));
        assertFalse(Crypt14.isNotNewerThan("2.26.38.74.1", "2.26.38.74"));
    }

    @Test
    public void a_version_that_cannot_be_compared_is_allowed_through() {
        // Refusing on an unreadable version string would block a legitimate
        // restore for no measured reason; the undo and BootHealth cover the
        // other direction, and the screen says the comparison was not possible.
        assertTrue(Crypt14.isNotNewerThan(null, "2.26.38.74"));
        assertTrue(Crypt14.isNotNewerThan("2.26.38.74", null));
        assertTrue(Crypt14.isNotNewerThan("beta-channel", "2.26.38.74"));
    }

    @Test
    public void sqlite_is_recognised_by_its_magic_and_nothing_else_is() {
        byte[] good = new byte[32];
        System.arraycopy("SQLite format 3".getBytes(StandardCharsets.US_ASCII), 0, good, 0, 15);
        assertTrue(Crypt14.looksLikeSqlite(good));
        // A wrong key cannot be detected during the decrypt because the GCM tag is
        // not verifiable, so the magic bytes are the first thing that can catch it.
        assertFalse(Crypt14.looksLikeSqlite(new byte[32]));
        assertFalse(Crypt14.looksLikeSqlite(new byte[]{1, 2, 3}));
    }

    @Test
    public void a_file_of_exactly_three_bytes_is_refused() {
        assertNull(Crypt14.parseHeader(new byte[]{1, 2, 3}));
    }

    @Test
    public void a_declared_header_length_exceeding_the_buffer_is_refused() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF); out.write(0x7F);  // varint 16383
        out.write(new byte[100]);  // but only 100 bytes of actual header
        assertNull(Crypt14.parseHeader(out.toByteArray()));
    }

    @Test
    public void a_length_delimited_field_running_past_the_header_end_is_refused() throws Exception {
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.write(0x2a); inner.write(100);  // field 5, claiming 100 bytes
        inner.write(new byte[16]);  // but only 16 bytes present
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(0x12); header.write(inner.size()); header.write(inner.toByteArray());
        assertNull(Crypt14.parseHeader(crypt14(header.toByteArray(), new byte[50])));
    }

    @Test
    public void a_nested_field_length_exceeding_its_parent_is_refused() throws Exception {
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.write(0x1a); inner.write(32); inner.write(new byte[32]);  // 2.3
        inner.write(0x2a); inner.write(100);  // 2.5, claiming 100 bytes but only 5 present
        inner.write(new byte[5]);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(0x12); header.write(inner.size()); header.write(inner.toByteArray());
        assertNull(Crypt14.parseHeader(crypt14(header.toByteArray(), new byte[50])));
    }

    @Test
    public void a_varint_with_no_terminator_is_refused() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < 10; i++) {
            out.write(0xFF);  // continuation bit set on every byte
        }
        assertNull(Crypt14.parseHeader(out.toByteArray()));
    }

    @Test
    public void a_field_number_that_aliases_field_two_under_a_32_bit_cast_is_refused() throws Exception {
        // tag 34359738386 -> field number 4294967298, wire type 2.
        // Two things are true of that number at once: it exceeds Integer.MAX_VALUE,
        // so the guard must reject it; and it truncates to exactly 2 in 32 bits, so
        // without the guard it would be walked as field 2 -- the IV's parent. Six
        // bytes, which is inside the varint reader's shift<=35 bound.
        byte[] header = new byte[]{
                (byte) 0x92, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01,
                16, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        };
        assertNull(Crypt14.parseHeader(crypt14(header, new byte[50])));
    }

    @Test
    public void a_header_longer_than_127_bytes_encodes_the_varint_across_two_bytes() throws Exception {
        // Build a header > 127 bytes by creating a large field 2
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        // field 2.1: 100 bytes
        inner.write(0x0a); inner.write(100); inner.write(new byte[100]);
        // field 2.5: 16 bytes (the IV)
        inner.write(0x2a); inner.write(16); inner.write(new byte[16]);
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(0x12); header.write(inner.size()); header.write(inner.toByteArray());
        header.write(0x22); header.write(4); header.write(new byte[]{9, 9, 9, 9});
        ByteArrayOutputStream outer = new ByteArrayOutputStream();
        int headerLen = header.size();  // Should be > 127
        outer.write(headerLen & 0x7f | 0x80);  // 2-byte varint encoding
        outer.write(headerLen >> 7);
        outer.write(header.toByteArray());
        outer.write(new byte[100]);
        Crypt14.Header h = Crypt14.parseHeader(outer.toByteArray());
        assertNotNull(h);
        assertEquals(2 + headerLen, h.cipherOffset);
    }
}
