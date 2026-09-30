package com.smali_generator.backup;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/**
 * Drive's q= parameter is a string language: a folder name holding a quote or a
 * backslash changes the query's meaning rather than failing it, which is how a
 * listing silently matches the wrong folder -- or everything.
 */
public class DriveClientTest {

    @Test
    public void a_plain_name_is_unchanged() {
        assertEquals("WhatsApp Patcher Backups",
                DriveClient.escapeQueryLiteral("WhatsApp Patcher Backups"));
    }

    @Test
    public void a_single_quote_is_escaped() {
        assertEquals("Alon\\'s backups", DriveClient.escapeQueryLiteral("Alon's backups"));
    }

    @Test
    public void a_backslash_is_escaped_before_quotes_are() {
        // Escaping quotes first would turn a backslash into an escape for the
        // escape, and the query would end early.
        assertEquals("a\\\\b", DriveClient.escapeQueryLiteral("a\\b"));
        assertEquals("a\\\\\\'b", DriveClient.escapeQueryLiteral("a\\'b"));
    }

    @Test
    public void the_sha256_of_a_known_file_is_the_known_digest() throws Exception {
        File f = File.createTempFile("driveclient", ".bin");
        f.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write("abc".getBytes("UTF-8"));
        }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                DriveClient.sha256(f));
    }

    @Test
    public void the_sha256_of_an_empty_file_is_the_empty_digest() throws Exception {
        File f = File.createTempFile("driveclient-empty", ".bin");
        f.deleteOnExit();
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                DriveClient.sha256(f));
    }

    @Test
    public void copy_reports_the_number_of_bytes_it_wrote() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(5000L, DriveClient.copy(new ByteArrayInputStream(new byte[5000]), out, Long.MAX_VALUE));
        assertEquals(5000, out.size());
    }

    @Test
    public void copy_stops_at_its_limit() throws Exception {
        assertEquals(100L,
                DriveClient.copy(new ByteArrayInputStream(new byte[5000]), new ByteArrayOutputStream(), 100L));
    }

    @Test
    public void a_body_that_ends_early_reports_what_actually_arrived() throws Exception {
        // This is how a truncated download is caught: the caller compares the
        // count against Content-Length instead of trusting that no exception
        // means the whole file arrived.
        assertEquals(10L,
                DriveClient.copy(new ByteArrayInputStream(new byte[10]), new ByteArrayOutputStream(), 1_000_000L));
    }
}
