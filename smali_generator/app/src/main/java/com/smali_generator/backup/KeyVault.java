package com.smali_generator.backup;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Wraps WhatsApp's files/key under a passphrase so the Drive copy is readable
 * without WhatsApp and unreadable by anyone who merely reaches the Drive folder.
 *
 * The file is wrapped whole and never parsed. Its layout (158 bytes, a
 * serialised byte[], the database key at offset 126) is a fact about one
 * release, and a wrapper that understood it would break on the release that
 * changed it -- or on the device whose local format is already crypt15.
 *
 * Pure Java by necessity, not taste: the module's unit tests have no Android
 * framework, so anything android.* here would be untestable, and this is the
 * one class where an untested bug costs the user every backup they have.
 */
public final class KeyVault {

    /** Tags our blobs so a foreign file fails on the magic rather than on the tag. */
    private static final byte[] MAGIC = {'W', 'A', 'P', 'K'};
    /** Bumped if the KDF or cipher changes, so old blobs stay readable. */
    private static final byte VERSION = 1;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;

    /**
     * OWASP's figure for PBKDF2-HMAC-SHA256. 210_000 is the SHA-512 number and
     * would be a quarter of the intended work here. About a second on a Pixel
     * 9a, paid once per wrap and once per unwrap, never on a hot path.
     */
    private static final int ITERATIONS = 600_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private KeyVault() {
    }

    /** Thrown for every way a blob can be wrong, so no caller can tell them apart. */
    public static class BadBlob extends Exception {
        BadBlob(String message) {
            super(message);
        }
    }

    public static byte[] wrap(byte[] plaintext, String passphrase) {
        try {
            byte[] salt = new byte[SALT_BYTES];
            RANDOM.nextBytes(salt);
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, derive(passphrase, salt),
                    new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plaintext);

            return ByteBuffer.allocate(MAGIC.length + 1 + SALT_BYTES + NONCE_BYTES + sealed.length)
                    .put(MAGIC).put(VERSION).put(salt).put(nonce).put(sealed).array();
        } catch (Exception e) {
            // Every one of these is a broken JCE provider, not user input.
            throw new IllegalStateException("cannot wrap the key", e);
        }
    }

    public static byte[] unwrap(byte[] blob, String passphrase) throws BadBlob {
        int header = MAGIC.length + 1 + SALT_BYTES + NONCE_BYTES;
        // A GCM tag is 16 bytes, so anything at or under the header plus a tag
        // carries no plaintext and cannot be one of ours.
        if (blob == null || blob.length <= header + 16) {
            throw new BadBlob("too short to be a key blob");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (blob[i] != MAGIC[i]) {
                throw new BadBlob("not a key blob");
            }
        }
        if (blob[MAGIC.length] != VERSION) {
            throw new BadBlob("key blob version " + blob[MAGIC.length] + " is not supported");
        }
        ByteBuffer in = ByteBuffer.wrap(blob);
        in.position(MAGIC.length + 1);
        byte[] salt = new byte[SALT_BYTES];
        in.get(salt);
        byte[] nonce = new byte[NONCE_BYTES];
        in.get(nonce);
        byte[] sealed = new byte[in.remaining()];
        in.get(sealed);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, derive(passphrase, salt),
                    new GCMParameterSpec(TAG_BITS, nonce));
            return cipher.doFinal(sealed);
        } catch (Exception e) {
            // A wrong passphrase and a tampered tag are the same failure, and
            // saying which would be telling an attacker something.
            throw new BadBlob("wrong passphrase, or the blob has been altered");
        }
    }

    /** A fresh salt for {@link #verifier}, base64 so it fits a settings string. */
    public static String newSaltB64() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /**
     * What gets stored so the restore screen can reject a wrong passphrase
     * before writing anything. Its salt is separate from any blob's, so the
     * stored value is not also something that opens one.
     */
    public static String verifier(String passphrase, String saltB64) {
        byte[] salt = Base64.getDecoder().decode(saltB64);
        return Base64.getEncoder().encodeToString(deriveBytes(passphrase, salt));
    }

    private static SecretKey derive(String passphrase, byte[] salt) {
        return new SecretKeySpec(deriveBytes(passphrase, salt), "AES");
    }

    private static byte[] deriveBytes(String passphrase, byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("cannot derive a key", e);
        }
    }
}
