package com.smali_generator.backup;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Where everything lives inside a crypt14, and nothing else.
 *
 * Measured on 2026-10-02 against two real backups, which agreed: a 2-byte
 * varint holding 194, then a 194-byte protobuf header, then the ciphertext at
 * 196. Inside the header, top-level field 2 is a nested message whose field 5
 * is the 16-byte IV (its field 4 is also 16 bytes, which is why "the second
 * one" is not a safe way to find it, and its field 3 is 32 bytes and looks like
 * a key fingerprint).
 *
 * Every method returns null rather than throwing or guessing. Getting an offset
 * wrong here does not fail loudly: it decrypts the wrong bytes with the right
 * key and yields plausible rubbish, so a shape this does not recognise must be
 * refused outright and left to a human.
 */
public final class Crypt14 {

    public static final int KEY_OFFSET = 126;
    public static final int KEY_LENGTH = 32;
    public static final String SQLITE_MAGIC = "SQLite format 3";
    /** Enough to cover the longest header seen plus its prefix, with room to spare. */
    public static final int SNIFF_BYTES = 512;

    private static final int IV_LENGTH = 16;
    private static final int OUTER_FIELD = 2;
    private static final int IV_FIELD = 5;
    private static final int META_FIELD = 4;
    private static final int VERSION_FIELD = 1;

    private Crypt14() {
    }

    public static final class Header {
        public final int headerLength;
        public final int cipherOffset;
        public final byte[] iv;
        /** The versionName of the WhatsApp that wrote it, or null if absent. */
        public final String waVersion;

        Header(int headerLength, int cipherOffset, byte[] iv, String waVersion) {
            this.headerLength = headerLength;
            this.cipherOffset = cipherOffset;
            this.iv = iv;
            this.waVersion = waVersion;
        }
    }

    public static Header parseHeader(byte[] firstBytes) {
        if (firstBytes == null || firstBytes.length < 4) {
            return null;
        }
        long[] cursor = {0};
        long length = varint(firstBytes, cursor);
        if (length < 0 || length > firstBytes.length - cursor[0] || length > 4096) {
            return null;
        }
        int headerStart = (int) cursor[0];
        int headerLength = (int) length;
        byte[] outer = findField(firstBytes, headerStart, headerStart + headerLength, OUTER_FIELD);
        if (outer == null) {
            return null;
        }
        byte[] iv = findField(outer, 0, outer.length, IV_FIELD);
        if (iv == null || iv.length != IV_LENGTH) {
            return null;
        }
        // Field 4.1 carries the writing app's versionName as text. Absent is not
        // fatal -- the version gate simply cannot be applied to such a file.
        String version = null;
        byte[] meta = findField(firstBytes, headerStart, headerStart + headerLength, META_FIELD);
        if (meta != null) {
            byte[] raw = findField(meta, 0, meta.length, VERSION_FIELD);
            if (raw != null && raw.length > 0 && raw.length < 32) {
                version = new String(raw, StandardCharsets.US_ASCII);
            }
        }
        return new Header(headerLength, headerStart + headerLength, iv, version);
    }

    /**
     * Whether a backup written by `backupVersion` is safe to open with
     * `appVersion`. WhatsApp upgrades an older schema and has no path for a
     * newer one, so a backup from ahead of the installed build must be refused
     * before anything is displaced.
     *
     * An unreadable version on either side returns true: refusing then would
     * block a legitimate restore for no measured reason, and the undo plus
     * BootHealth's safe-mode ladder cover the other direction.
     */
    public static boolean isNotNewerThan(String backupVersion, String appVersion) {
        int[] backup = versionParts(backupVersion);
        int[] app = versionParts(appVersion);
        if (backup == null || app == null) {
            return true;
        }
        for (int i = 0; i < Math.max(backup.length, app.length); i++) {
            int b = i < backup.length ? backup[i] : 0;
            int a = i < app.length ? app[i] : 0;
            if (b != a) {
                return b < a;
            }
        }
        return true;
    }

    private static int[] versionParts(String version) {
        if (version == null || version.isEmpty()) {
            return null;
        }
        String[] pieces = version.split("\\.");
        int[] out = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            try {
                out[i] = Integer.parseInt(pieces[i]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    /**
     * The bytes of the first length-delimited field with this number, or null.
     * Walks only the level it is given: the IV is field 5 of the message at
     * field 2, and conflating the two levels is how field 2.4 gets mistaken for
     * it.
     */
    private static byte[] findField(byte[] b, int from, int to, int wanted) {
        long[] cursor = {from};
        while (cursor[0] < to) {
            long tag = varint(b, cursor);
            if (tag < 0) {
                return null;
            }
            long number = tag >> 3;
            // Reject field numbers that exceed 32-bit range to prevent aliasing.
            if (number > Integer.MAX_VALUE) {
                return null;
            }
            int wire = (int) (tag & 7);
            if (wire == 2) {
                long length = varint(b, cursor);
                if (length < 0 || cursor[0] + length > to) {
                    return null;
                }
                int start = (int) cursor[0];
                cursor[0] += length;
                if (number == wanted) {
                    byte[] out = new byte[(int) length];
                    System.arraycopy(b, start, out, 0, out.length);
                    return out;
                }
            } else if (wire == 0) {
                if (varint(b, cursor) < 0) {
                    return null;
                }
            } else if (wire == 5) {
                cursor[0] += 4;
            } else if (wire == 1) {
                cursor[0] += 8;
            } else {
                return null;
            }
        }
        return null;
    }

    /** -1 for a varint that runs off the end or is implausibly long. */
    private static long varint(byte[] b, long[] cursor) {
        long value = 0;
        int shift = 0;
        while (shift <= 35) {
            if (cursor[0] >= b.length) {
                return -1;
            }
            int x = b[(int) cursor[0]++] & 0xff;
            value |= (long) (x & 0x7f) << shift;
            if ((x & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        return -1;
    }

    /**
     * The 32 bytes at offset 126 of files/key. KeyVault deliberately does not
     * parse that file, so this offset exists in exactly one place -- here.
     */
    public static byte[] databaseKey(File keyFile) {
        if (keyFile == null || !keyFile.isFile() || keyFile.length() < KEY_OFFSET + KEY_LENGTH) {
            return null;
        }
        byte[] key = new byte[KEY_LENGTH];
        try (InputStream in = new FileInputStream(keyFile)) {
            long skipped = 0;
            while (skipped < KEY_OFFSET) {
                long n = in.skip(KEY_OFFSET - skipped);
                if (n <= 0) {
                    return null;
                }
                skipped += n;
            }
            int read = 0;
            while (read < KEY_LENGTH) {
                int n = in.read(key, read, KEY_LENGTH - read);
                if (n < 0) {
                    return null;
                }
                read += n;
            }
        } catch (IOException e) {
            return null;
        }
        return key;
    }

    /**
     * The decrypt cannot tell a right key from a wrong one -- GCM's tag is not
     * verifiable here -- so this magic is the first thing that can. A wrong key
     * yields bytes that fail it.
     */
    public static boolean looksLikeSqlite(byte[] firstBytes) {
        byte[] magic = SQLITE_MAGIC.getBytes(StandardCharsets.US_ASCII);
        if (firstBytes == null || firstBytes.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (firstBytes[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
