package com.smali_generator.backup;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The crypt14 files WhatsApp has already written to its own backup folder.
 *
 * Only .crypt14, and only non-empty regular files: the restore screen offers
 * whatever this returns, and every entry is something a person can ask to have
 * decrypted over their chat history. A crypt15 is deliberately not offered --
 * its key is not on the device, so it cannot be restored this way at all.
 */
public final class LocalBackups {

    private static final String SUFFIX = ".crypt14";

    private LocalBackups() {
    }

    public static final class Entry {
        public final File file;
        public final String name;
        public final long bytes;
        public final long modifiedAtMs;

        Entry(File file) {
            this.file = file;
            this.name = file.getName();
            this.bytes = file.length();
            this.modifiedAtMs = file.lastModified();
        }
    }

    public static boolean isCrypt14(String name) {
        return name != null && name.endsWith(SUFFIX);
    }

    public static List<Entry> list(File databasesDir) {
        List<Entry> out = new ArrayList<>();
        if (databasesDir == null) {
            return out;
        }
        File[] children = databasesDir.listFiles();
        if (children == null) {
            return out;
        }
        for (File c : children) {
            if (c.isFile() && c.length() > 0 && isCrypt14(c.getName())) {
                out.add(new Entry(c));
            }
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Long.compare(b.modifiedAtMs, a.modifiedAtMs);
            }
        });
        return out;
    }
}
