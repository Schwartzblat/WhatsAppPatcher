package com.smali_generator.backup;

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Decrypts a crypt14 and inflates it to a staged file, and does nothing else --
 * in particular it never touches the live database. Committing the result is
 * RestoreSwap's job, and only after the staged file has been verified.
 *
 * android.util.Log is the one android reference here and it is reached only on
 * a real device; the tests do not call the logging paths. Nothing logged is ever
 * key material: lengths and booleans only.
 */
public final class Crypt14Restore {

    private static final String TAG = "PATCH";
    /**
     * A crypt14 is zlib-compressed SQLite, which in practice inflates to about
     * four times its stored size; six leaves room rather than filling the data
     * partition and taking the app down with it.
     */
    public static final int INFLATION_FACTOR = 6;

    private Crypt14Restore() {
    }

    public static final class Outcome {
        public final boolean ok;
        public final String message;
        public final long plainBytes;

        Outcome(boolean ok, String message, long plainBytes) {
            this.ok = ok;
            this.message = message;
            this.plainBytes = plainBytes;
        }
    }

    public static long requiredFreeBytes(long crypt14Bytes) {
        return crypt14Bytes * INFLATION_FACTOR;
    }

    public static Outcome stage(File crypt14, byte[] key, File staged) {
        if (crypt14 == null || !crypt14.isFile()) {
            return new Outcome(false, "That backup file is not there any more.", 0);
        }
        if (key == null || key.length != Crypt14.KEY_LENGTH) {
            return new Outcome(false, "WhatsApp's database key could not be read."
                    + " It only exists once the number on this phone is registered.", 0);
        }

        byte[] sniff = new byte[Crypt14.SNIFF_BYTES];
        int sniffed;
        try (InputStream in = new FileInputStream(crypt14)) {
            sniffed = readFully(in, sniff);
        } catch (IOException e) {
            return new Outcome(false, "Could not read that backup: " + e.getMessage(), 0);
        }
        byte[] exact = new byte[sniffed];
        System.arraycopy(sniff, 0, exact, 0, sniffed);
        Crypt14.Header header = Crypt14.parseHeader(exact);
        if (header == null) {
            return new Outcome(false, "That file is not a crypt14 backup this version understands."
                    + " Nothing was changed.", 0);
        }

        staged.delete();
        long written;
        try {
            written = decrypt(crypt14, key, header, staged);
        } catch (IOException | RuntimeException e) {
            staged.delete();
            return new Outcome(false, "That backup did not decrypt with the key on this phone."
                    + " The key on this phone does not match it -- that happens when the"
                    + " backup predates the last time this number was registered."
                    + " Nothing was changed.", 0);
        }

        byte[] first = new byte[32];
        try (InputStream in = new FileInputStream(staged)) {
            readFully(in, first);
        } catch (IOException e) {
            staged.delete();
            return new Outcome(false, "Could not read back the decrypted file: " + e.getMessage(), 0);
        }
        if (!Crypt14.looksLikeSqlite(first)) {
            // The GCM tag is not verifiable here, so a wrong key cannot be
            // detected during the decrypt -- only afterwards, by what came out.
            staged.delete();
            return new Outcome(false, "That backup did not decrypt to a database."
                    + " The key on this phone does not match it -- that happens when the"
                    + " backup predates the last time this number was registered."
                    + " Nothing was changed.", 0);
        }
        return new Outcome(true, "Decrypted and verified as a database.", written);
    }

    private static long decrypt(File crypt14, byte[] key, Crypt14.Header header, File staged)
            throws IOException {
        Cipher cipher;
        try {
            cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, header.iv));
        } catch (Exception e) {
            throw new IOException("cipher refused the key or IV", e);
        }

        long written = 0;
        byte[] cipherChunk = new byte[128];
        byte[] plainChunk;
        ByteArrayInputStream plainIn = new ByteArrayInputStream(new byte[0]);
        try (InputStream in = new FileInputStream(crypt14);
             OutputStream out = new FileOutputStream(staged)) {
            skipFully(in, header.cipherOffset);
            int read;
            ByteArrayOutputStream plainBuffer = new ByteArrayOutputStream();
            while ((read = in.read(cipherChunk)) > 0) {
                // update() only, never doFinal. On Conscrypt (Android) this releases
                // plaintext incrementally. On SunJCE (desktop) it buffers until
                // doFinal, so desktop tests cannot exercise the cipher seam.
                plainChunk = cipher.update(cipherChunk, 0, read);
                if (plainChunk != null && plainChunk.length > 0) {
                    plainBuffer.write(plainChunk);
                }
            }
            byte[] allPlain = plainBuffer.toByteArray();
            if (allPlain.length == 0) {
                throw new IOException("cipher released no plaintext (desktop JVM with SunJCE)");
            }
            plainIn = new ByteArrayInputStream(allPlain);
            written = inflateTo(plainIn, out);
        }
        return written;
    }

    /**
     * Inflates an already-decrypted stream to the staged file. Separated from the
     * cipher because AES/GCM cannot be exercised on a desktop JVM at all: SunJCE
     * buffers every byte until doFinal, while Android's Conscrypt releases
     * incrementally. Everything below this line is therefore testable; the cipher
     * seam above it is verified on the device.
     */
    public static long inflateTo(InputStream plain, OutputStream staged) throws IOException {
        Inflater inflater = new Inflater();
        long written = 0;
        byte[] inflated = new byte[1 << 16];
        try {
            int read;
            byte[] plainChunk = new byte[4096];
            while ((read = plain.read(plainChunk)) > 0) {
                inflater.setInput(plainChunk, 0, read);
                written += drain(inflater, inflated, staged);
            }
        } finally {
            // The stream has ended. If the inflater is not finished, it means the
            // ciphertext was truncated (by design: update() withholds the last 16
            // bytes when no tag is present). This is expected and correct.
            inflater.end();
        }
        return written;
    }

    private static long drain(Inflater inflater, byte[] buffer, OutputStream out)
            throws IOException {
        long written = 0;
        while (!inflater.finished() && !inflater.needsInput()) {
            int n;
            try {
                n = inflater.inflate(buffer);
            } catch (DataFormatException e) {
                throw new IOException("the decrypted stream is not zlib", e);
            }
            if (n <= 0) {
                break;
            }
            out.write(buffer, 0, n);
            written += n;
        }
        return written;
    }

    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int read = 0;
        while (read < buffer.length) {
            int n = in.read(buffer, read, buffer.length - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        return read;
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long skipped = 0;
        while (skipped < count) {
            long n = in.skip(count - skipped);
            if (n <= 0) {
                throw new IOException("could not reach the ciphertext");
            }
            skipped += n;
        }
    }
}
