package com.smali_generator.backup;

import java.util.HashMap;
import java.util.Map;

/**
 * The text file that rides beside each run in Drive, so a backup can be
 * identified without downloading it and verified after.
 *
 * key=value lines rather than JSON: these unit tests have no Android framework,
 * so Android's bundled org.json is not available to them, and a manifest a
 * person can read in the Drive web UI is worth more here than nesting.
 *
 * parse() never throws. A manifest is the one thing read back from somewhere
 * this code did not write, and a run list that dies on one bad file is worse
 * than one that skips it.
 */
public final class BackupManifest {

    public static final String FILE_NAME = "manifest.txt";

    public final String runId;
    public final String dbFileName;
    public final long dbSize;
    public final String dbSha256;
    public final String crypt;
    public final boolean keyIncluded;
    public final String waVersion;
    public final long createdAtMs;

    public BackupManifest(String runId, String dbFileName, long dbSize, String dbSha256,
                          String crypt, boolean keyIncluded, String waVersion, long createdAtMs) {
        this.runId = runId;
        this.dbFileName = dbFileName;
        this.dbSize = dbSize;
        this.dbSha256 = dbSha256;
        this.crypt = crypt;
        this.keyIncluded = keyIncluded;
        this.waVersion = waVersion;
        this.createdAtMs = createdAtMs;
    }

    public String render() {
        return "manifest_version=1\n"
                + "run_id=" + runId + "\n"
                + "created_at_ms=" + createdAtMs + "\n"
                + "wa_version=" + waVersion + "\n"
                + "db_file=" + dbFileName + "\n"
                + "db_size=" + dbSize + "\n"
                + "db_sha256=" + dbSha256 + "\n"
                + "crypt=" + crypt + "\n"
                + "key_included=" + keyIncluded + "\n";
    }

    public static BackupManifest parse(String text) {
        if (text == null) {
            return null;
        }
        Map<String, String> fields = new HashMap<>();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq > 0) {
                fields.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        try {
            String runId = fields.get("run_id");
            String dbFile = fields.get("db_file");
            String sha = fields.get("db_sha256");
            String size = fields.get("db_size");
            if (runId == null || dbFile == null || sha == null || sha.isEmpty() || size == null) {
                return null;
            }
            return new BackupManifest(runId, dbFile, Long.parseLong(size), sha,
                    fields.get("crypt"), Boolean.parseBoolean(fields.get("key_included")),
                    fields.get("wa_version"),
                    Long.parseLong(fields.getOrDefault("created_at_ms", "0")));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
