package com.smali_generator.backup;

import android.util.Log;

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
 * android.util.Log is the one android reference here. It is reached through
 * log(), which swallows the stub's "not mocked" failure so the desktop tests can
 * run stage(); on a device it is the only way to see how far a decrypt got.
 * Nothing logged is ever key material: counts only.
 */
public final class Crypt14Restore {

    private static final String TAG = "PATCH";
    /**
     * A crypt14 is zlib-compressed SQLite, which in practice inflates to about
     * four times its stored size; six leaves room rather than filling the data
     * partition and taking the app down with it.
     */
    public static final int INFLATION_FACTOR = 6;

    /**
     * Shown when the cipher itself released nothing. On Android that cannot
     * happen; stage() matches on it to tell a provider fault from a wrong key.
     */
    static final String NO_PLAINTEXT = "This phone's crypto provider returned no data,"
            + " which should not happen on Android. Nothing was changed.";

    /**
     * Shown when the staged file could not be written. Kept apart from a wrong
     * key: sending somebody to re-register their number over a full disk is the
     * worst available advice. INFLATION_FACTOR is an estimate over a measured
     * ~4x and the free-space check is made before the write, not during it.
     */
    static final String DISK_FULL = "The database could not be written to this phone's storage,"
            + " most likely because it is full. Free some space and try again."
            + " Nothing was changed.";

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
            return new Outcome(false, "That backup file is not there any more. Nothing was changed.", 0);
        }
        if (key == null || key.length != Crypt14.KEY_LENGTH) {
            return new Outcome(false, "WhatsApp's database key could not be read."
                    + " It only exists once the number on this phone is registered."
                    + " Nothing was changed.", 0);
        }

        byte[] sniff = new byte[Crypt14.SNIFF_BYTES];
        int sniffed;
        try (InputStream in = new FileInputStream(crypt14)) {
            sniffed = readFully(in, sniff);
        } catch (IOException e) {
            return new Outcome(false, "Could not read that backup: " + e.getMessage()
                    + " Nothing was changed.", 0);
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
        String decryptError = null;
        long[] counts = {0, -1};
        try {
            written = decrypt(crypt14, key, header, staged, counts);
            log(true, counts);
        } catch (IOException e) {
            staged.delete();
            log(false, counts);
            decryptError = e.getMessage();
            // A silent cipher is a provider fault, not a wrong key; keep them apart.
            if (NO_PLAINTEXT.equals(decryptError) || DISK_FULL.equals(decryptError)) {
                return new Outcome(false, decryptError, 0);
            }
            return new Outcome(false, "That backup did not decrypt with the key on this phone."
                    + " The key on this phone does not match it -- that happens when the"
                    + " backup predates the last time this number was registered."
                    + " Nothing was changed.", 0);
        } catch (RuntimeException e) {
            staged.delete();
            log(false, counts);
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
            return new Outcome(false, "Could not read back the decrypted file: " + e.getMessage()
                    + " Nothing was changed.", 0);
        }
        if (!Crypt14.looksLikeSqlite(first)) {
            staged.delete();
            return new Outcome(false, "That backup did not decrypt to a database."
                    + " The key on this phone does not match it -- that happens when the"
                    + " backup predates the last time this number was registered."
                    + " Nothing was changed.", 0);
        }
        return new Outcome(true, "Decrypted and verified as a database.", written);
    }

    /**
     * What the cipher released against what the inflater made of it is the only
     * way to tell a provider that yields nothing from a key that yields noise,
     * and only a device can show it. -1 means the inflater was never reached.
     */
    static String countsLine(boolean ok, long released, long written) {
        return "Crypt14Restore: " + (ok ? "staged" : "failed") + ", released=" + released
                + ", written=" + written;
    }

    private static void log(boolean ok, long[] counts) {
        try {
            Log.i(TAG, countsLine(ok, counts[0], counts[1]));
        } catch (RuntimeException ignored) {
            // A log line must never be what fails a restore; on a desktop JVM
            // the framework stub throws here.
        }
    }

    /** counts receives {released, written}, filled in as far as the run got. */
    private static long decrypt(File crypt14, byte[] key, Crypt14.Header header, File staged,
                                long[] counts) throws IOException {
        Cipher cipher;
        try {
            cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, header.iv));
        } catch (Exception e) {
            throw new IOException("cipher refused the key or IV", e);
        }

        try (InputStream cipherIn = new FileInputStream(crypt14);
             CipherUpdateStream plainIn = new CipherUpdateStream(cipherIn, cipher, header.cipherOffset);
             FileOutputStream out = new FileOutputStream(staged)) {
            long written;
            try {
                written = inflateTo(plainIn, out);
            } finally {
                counts[0] = plainIn.released();
            }
            counts[1] = written;
            // Two different quantities: what the cipher released says whether the
            // provider works; what the inflater made of it says whether the key did.
            if (plainIn.released() == 0) {
                throw new IOException(NO_PLAINTEXT);
            }
            if (written == 0) {
                throw new IOException("the released bytes inflated to nothing");
            }
            // The swap happens in a later process: a power loss between the two
            // would otherwise commit a database whose tail never reached the disk.
            out.getFD().sync();
            return written;
        }
    }

    /**
     * Releases cipher.update() output as a stream, so a multi-hundred-megabyte
     * backup is never resident in memory. Deliberately NOT CipherInputStream:
     * that calls doFinal on close, which on a tagless crypt14 throws after the
     * whole file has been read — the exact failure a probe hit on 2026-09-30,
     * inflating 30 MB and then dying at close.
     */
    private static final class CipherUpdateStream extends InputStream {
        private final InputStream cipherIn;
        private final Cipher cipher;
        private final byte[] cipherChunk = new byte[16 * 1024];
        private byte[] plainBuffer = new byte[0];
        private int plainPos = 0;
        private boolean eof = false;
        private long released = 0;

        CipherUpdateStream(InputStream cipherIn, Cipher cipher, long skipBytes) throws IOException {
            this.cipherIn = cipherIn;
            this.cipher = cipher;
            skipFully(cipherIn, skipBytes);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (off < 0 || len < 0 || off + len > b.length) {
                throw new IndexOutOfBoundsException();
            }
            if (len == 0) {
                return 0;
            }

            int copied = 0;
            while (copied < len) {
                if (plainPos < plainBuffer.length) {
                    int n = Math.min(len - copied, plainBuffer.length - plainPos);
                    System.arraycopy(plainBuffer, plainPos, b, off + copied, n);
                    plainPos += n;
                    copied += n;
                } else if (!eof) {
                    int read = cipherIn.read(cipherChunk);
                    if (read < 0) {
                        eof = true;
                        if (copied == 0) {
                            return -1;
                        }
                        break;
                    }
                    plainBuffer = cipher.update(cipherChunk, 0, read);
                    if (plainBuffer == null) {
                        plainBuffer = new byte[0];
                    }
                    released += plainBuffer.length;
                    plainPos = 0;
                } else {
                    break;
                }
            }
            return copied > 0 ? copied : -1;
        }

        long released() {
            return released;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n < 0 ? -1 : b[0] & 0xff;
        }
    }

    /**
     * Inflates an already-decrypted stream to the staged file. Separated from the
     * cipher because AES/GCM cannot be exercised on a desktop JVM at all: SunJCE
     * buffers every byte until doFinal, while Android's Conscrypt releases
     * incrementally. Everything below this line is therefore testable; the cipher
     * seam above it is verified on the device.
     */
    static long inflateTo(InputStream plain, OutputStream staged) throws IOException {
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
            try {
                out.write(buffer, 0, n);
            } catch (IOException e) {
                // Not the stream's fault and not the key's: the disk refused.
                throw new IOException(DISK_FULL, e);
            }
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
