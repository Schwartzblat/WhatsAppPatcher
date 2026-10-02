package com.smali_generator.backup;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Only the pure decision: the SQLite calls need a device and stay untested. */
public class DatabaseCheckTest {

    private static Set<String> set(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    @Test
    public void realisticMessageStorePasses() {
        assertTrue(DatabaseCheck.looksLikeMessageStore(set(
                "message", "chat", "jid", "message_media", "receipt_user",
                "message_system", "message_mentions", "jid_map", "lid_display_name")));
    }

    @Test
    public void contactsDatabaseIsRefused() {
        assertFalse(DatabaseCheck.looksLikeMessageStore(set(
                "wa_contacts", "wa_contacts_fts", "wa_contacts_fts_content",
                "wa_contacts_fts_segments", "wa_contacts_fts_segdir", "wa_props",
                "android_metadata", "sqlite_sequence")));
    }

    @Test
    public void exactlyThreePasses() {
        assertTrue(DatabaseCheck.looksLikeMessageStore(set("message", "chat", "jid", "other")));
    }

    @Test
    public void exactlyTwoIsRefused() {
        assertFalse(DatabaseCheck.looksLikeMessageStore(set("message", "chat", "other", "more")));
    }

    @Test
    public void emptyAndNullAreRefused() {
        assertFalse(DatabaseCheck.looksLikeMessageStore(set()));
        assertFalse(DatabaseCheck.looksLikeMessageStore(null));
    }

    @Test
    public void caseDoesNotMatterBecauseSqliteNamesAreCaseInsensitive() {
        assertTrue(DatabaseCheck.looksLikeMessageStore(set("Message", "CHAT", "Jid")));
    }
}
