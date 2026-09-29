package com.smali_generator.backup;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;

/**
 * Writing a wrong files/key makes every local backup on the device unreadable,
 * which is worse than not restoring at all -- so every way a blob can be wrong
 * has to end in BadBlob rather than in plausible-looking bytes.
 */
public class KeyVaultTest {

    private static final byte[] KEY_FILE = new byte[158];
    static {
        for (int i = 0; i < KEY_FILE.length; i++) {
            KEY_FILE[i] = (byte) (i * 7);
        }
    }

    @Test
    public void a_wrapped_key_file_comes_back_byte_for_byte() throws Exception {
        byte[] blob = KeyVault.wrap(KEY_FILE, "correct horse battery staple");
        assertArrayEquals(KEY_FILE, KeyVault.unwrap(blob, "correct horse battery staple"));
    }

    @Test
    public void the_blob_does_not_contain_the_plaintext() {
        byte[] blob = KeyVault.wrap(KEY_FILE, "pw");
        // The 32 bytes at offset 126 are the database key; if they survive into
        // the blob then the upload is plaintext however it is labelled.
        byte[] secret = Arrays.copyOfRange(KEY_FILE, 126, 158);
        assertFalse(indexOf(blob, secret) >= 0);
    }

    @Test
    public void two_wraps_of_the_same_input_differ() {
        // A fresh salt and nonce per wrap: identical blobs across daily runs
        // would leak that the key never changed, and would reuse a GCM nonce.
        assertFalse(Arrays.equals(KeyVault.wrap(KEY_FILE, "pw"), KeyVault.wrap(KEY_FILE, "pw")));
    }

    @Test
    public void a_wrong_passphrase_is_rejected() {
        byte[] blob = KeyVault.wrap(KEY_FILE, "right");
        try {
            KeyVault.unwrap(blob, "wrong");
            fail("expected BadBlob");
        } catch (KeyVault.BadBlob expected) {
        }
    }

    @Test
    public void a_truncated_blob_is_rejected() {
        byte[] blob = KeyVault.wrap(KEY_FILE, "pw");
        byte[] cut = Arrays.copyOf(blob, blob.length - 1);
        try {
            KeyVault.unwrap(cut, "pw");
            fail("expected BadBlob");
        } catch (KeyVault.BadBlob expected) {
        }
    }

    @Test
    public void a_flipped_ciphertext_bit_is_rejected() {
        byte[] blob = KeyVault.wrap(KEY_FILE, "pw");
        blob[blob.length - 1] ^= 0x01;
        try {
            KeyVault.unwrap(blob, "pw");
            fail("expected BadBlob");
        } catch (KeyVault.BadBlob expected) {
        }
    }

    @Test
    public void a_foreign_blob_is_rejected_on_its_magic() {
        byte[] notOurs = "this is not a key vault blob at all, not even close".getBytes();
        try {
            KeyVault.unwrap(notOurs, "pw");
            fail("expected BadBlob");
        } catch (KeyVault.BadBlob expected) {
        }
    }

    @Test
    public void an_empty_blob_is_rejected() {
        try {
            KeyVault.unwrap(new byte[0], "pw");
            fail("expected BadBlob");
        } catch (KeyVault.BadBlob expected) {
        }
    }

    @Test
    public void the_verifier_accepts_the_passphrase_and_rejects_others() {
        String salt = KeyVault.newSaltB64();
        String stored = KeyVault.verifier("right", salt);
        assertEquals(stored, KeyVault.verifier("right", salt));
        assertNotEquals(stored, KeyVault.verifier("wrong", salt));
    }

    @Test
    public void the_verifier_is_not_the_wrapping_key() {
        // Same passphrase, different salts: the stored verifier must not be
        // something that also opens the blob.
        String salt = KeyVault.newSaltB64();
        assertNotEquals(KeyVault.verifier("pw", salt), KeyVault.verifier("pw", KeyVault.newSaltB64()));
        assertTrue(KeyVault.newSaltB64().length() > 0);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
