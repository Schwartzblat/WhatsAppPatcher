package com.smali_generator.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BackupManifestTest {

    private static BackupManifest sample() {
        return new BackupManifest("2026-09-30T020304Z", "msgstore.db.crypt14", 13_421_772L,
                "abc123", "crypt14", true, "2.26.37.74", 1790000000000L);
    }

    @Test
    public void a_rendered_manifest_parses_back_to_the_same_values() {
        BackupManifest back = BackupManifest.parse(sample().render());
        assertEquals("2026-09-30T020304Z", back.runId);
        assertEquals("msgstore.db.crypt14", back.dbFileName);
        assertEquals(13_421_772L, back.dbSize);
        assertEquals("abc123", back.dbSha256);
        assertEquals("crypt14", back.crypt);
        assertTrue(back.keyIncluded);
        assertEquals("2.26.37.74", back.waVersion);
        assertEquals(1790000000000L, back.createdAtMs);
    }

    @Test
    public void a_manifest_without_a_key_says_so() {
        BackupManifest m = new BackupManifest("r", "f", 1L, "s", "crypt14", false, "v", 2L);
        assertTrue(BackupManifest.parse(m.render()) != null);
        assertEquals(false, BackupManifest.parse(m.render()).keyIncluded);
    }

    @Test
    public void unknown_lines_and_blank_lines_are_ignored() {
        // A manifest written by a later version must still be readable by this
        // one, or an upgrade strands every run already in Drive.
        String text = sample().render() + "\nsomething_new=42\n\n# a comment\n";
        assertEquals("abc123", BackupManifest.parse(text).dbSha256);
    }

    @Test
    public void rubbish_parses_to_null_rather_than_throwing() {
        assertNull(BackupManifest.parse("not a manifest"));
        assertNull(BackupManifest.parse(""));
        assertNull(BackupManifest.parse(null));
    }

    @Test
    public void a_manifest_missing_a_required_field_is_null() {
        String text = sample().render().replaceAll("(?m)^db_sha256=.*$", "");
        assertNull(BackupManifest.parse(text));
    }

    @Test
    public void a_non_numeric_size_is_null_rather_than_an_exception() {
        String text = sample().render().replaceAll("(?m)^db_size=.*$", "db_size=banana");
        assertNull(BackupManifest.parse(text));
    }
}
