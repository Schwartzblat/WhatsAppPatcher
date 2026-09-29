package com.smali_generator.backup;

import android.content.Context;
import android.util.Log;

import com.smali_generator.db.PatchDb;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * One backup run: pick the newest local backup, wrap the key if there is a
 * passphrase, upload both and a manifest, then trim old runs.
 *
 * WhatsApp writes its local backup itself at about 02:00 regardless of its own
 * Automatic-backups setting, so this never makes a backup -- it only ferries
 * one. That is also why a run with nothing newer to send is a success, not a
 * failure.
 */
public final class BackupRunner {

    private static final String TAG = "PATCH";

    public static final String DATABASES_DIR =
            "/sdcard/Android/media/com.whatsapp/WhatsApp/Databases";
    public static final String ROOT_FOLDER = "WhatsApp Patcher Backups";
    public static final String KEY_BLOB_NAME = "key.enc";

    public static final String LAST_RESULT_KEY = "drive_backup_last_result";
    public static final String LAST_RUN_AT_KEY = "drive_backup_last_run_at";
    public static final String LAST_SHA_KEY = "drive_backup_last_sha";
    public static final String RETENTION_KEY = "drive_backup_retention";
    public static final String PASSPHRASE_VERIFIER_KEY = "drive_backup_pass_verifier";
    public static final String PASSPHRASE_SALT_KEY = "drive_backup_pass_salt";
    public static final int DEFAULT_RETENTION = 7;

    private BackupRunner() {
    }

    /**
     * The newest msgstore backup in the folder, by modification time.
     *
     * Null for a directory that does not exist or holds none: a fresh install
     * has no backup yet, and the run has to skip rather than throw.
     */
    public static File newestBackup(File databasesDir) {
        if (databasesDir == null || !databasesDir.isDirectory()) {
            return null;
        }
        File[] all = databasesDir.listFiles();
        if (all == null) {
            return null;
        }
        File best = null;
        for (File f : all) {
            if (!f.isFile() || !f.getName().startsWith("msgstore") || cryptOf(f.getName()) == null) {
                continue;
            }
            if (best == null || f.lastModified() > best.lastModified()) {
                best = f;
            }
        }
        return best;
    }

    public static String cryptOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        if (fileName.endsWith(".crypt14")) {
            return "crypt14";
        }
        if (fileName.endsWith(".crypt15")) {
            return "crypt15";
        }
        return null;
    }

    /** Zero-padded UTC, so Retention can sort run ids as plain strings. */
    public static String runId(long whenMs) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HHmmss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(whenMs));
    }

    /** Blocks on network. Never call from the main thread. */
    public static String runOnce(Context context) {
        String outcome = doRun(context);
        PatchDb.setString(LAST_RESULT_KEY, outcome);
        PatchDb.setString(LAST_RUN_AT_KEY, String.valueOf(System.currentTimeMillis()));
        Log.i(TAG, "BackupRunner: " + outcome);
        return outcome;
    }

    private static String doRun(Context context) {
        File source = newestBackup(new File(DATABASES_DIR));
        if (source == null) {
            return "No local backup to send yet";
        }
        String sha = DriveClient.sha256(source);
        if (sha == null) {
            return "Could not read the local backup";
        }
        if (sha.equals(PatchDb.getString(LAST_SHA_KEY, null))) {
            return "Already up to date";
        }

        GoogleAuth.Token token = GoogleAuth.token(context);
        if (token.consent != null) {
            return "Google Drive needs to be reconnected";
        }
        if (token.value == null) {
            return "No Google account connected";
        }

        DriveClient drive = new DriveClient(context, token.value);
        String root = drive.folderId(ROOT_FOLDER, null);
        if (root == null && drive.unauthorized) {
            // One retry with a fresh token: the cached one expires silently.
            GoogleAuth.invalidate(context, token.value);
            token = GoogleAuth.token(context);
            if (token.value == null) {
                return "Google Drive needs to be reconnected";
            }
            drive = new DriveClient(context, token.value);
            root = drive.folderId(ROOT_FOLDER, null);
        }
        if (root == null) {
            return "Could not open the Drive folder";
        }

        String id = runId(System.currentTimeMillis());
        String runFolder = drive.folderId(id, root);
        if (runFolder == null) {
            return "Could not create the run folder";
        }
        if (drive.upload(runFolder, source.getName(), "application/octet-stream", source) == null) {
            return "Upload failed";
        }

        boolean keyIncluded = uploadKey(context, drive, runFolder);
        File manifest = writeManifest(context, id, source, sha, keyIncluded);
        if (manifest != null) {
            drive.upload(runFolder, BackupManifest.FILE_NAME, "text/plain", manifest);
            // It lives in the cache dir; leaving it costs nothing but tidiness.
            manifest.delete();
        }

        PatchDb.setString(LAST_SHA_KEY, sha);
        trim(drive, root);
        return "Backed up " + source.getName() + (keyIncluded ? " with its key" : " without a key");
    }

    /**
     * The key is uploaded only when a passphrase is set. Without one the
     * database still goes -- WhatsApp restore refetches the key when the number
     * is verified -- but the archive half is inert, and the screen says so.
     */
    private static boolean uploadKey(Context context, DriveClient drive, String runFolder) {
        String passphrase = PassphraseHolder.get();
        if (passphrase == null) {
            return false;
        }
        File source = new File(context.getFilesDir(), "key");
        if (!source.isFile()) {
            Log.w(TAG, "BackupRunner: no files/key to wrap");
            return false;
        }
        File wrapped = null;
        try {
            byte[] blob = KeyVault.wrap(Files.readAllBytes(source.toPath()), passphrase);
            wrapped = new File(context.getCacheDir(), KEY_BLOB_NAME);
            try (FileOutputStream out = new FileOutputStream(wrapped)) {
                out.write(blob);
            }
            return drive.upload(runFolder, KEY_BLOB_NAME, "application/octet-stream", wrapped) != null;
        } catch (Exception e) {
            Log.e(TAG, "BackupRunner: cannot wrap the key", e);
            return false;
        } finally {
            if (wrapped != null) {
                wrapped.delete();
            }
        }
    }

    private static File writeManifest(Context context, String id, File source, String sha,
                                      boolean keyIncluded) {
        try {
            String version;
            try {
                version = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionName;
            } catch (Exception e) {
                version = "unknown";
            }
            BackupManifest manifest = new BackupManifest(id, source.getName(), source.length(),
                    sha, cryptOf(source.getName()), keyIncluded, version,
                    System.currentTimeMillis());
            File file = new File(context.getCacheDir(), BackupManifest.FILE_NAME);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(manifest.render().getBytes("UTF-8"));
            }
            return file;
        } catch (Exception e) {
            Log.e(TAG, "BackupRunner: cannot write the manifest", e);
            return null;
        }
    }

    private static void trim(DriveClient drive, String root) {
        try {
            List<String> ids = new ArrayList<>();
            List<DriveClient.Entry> runs = drive.children(root);
            for (DriveClient.Entry entry : runs) {
                ids.add(entry.name);
            }
            List<String> doomed = Retention.toDelete(ids,
                    PatchDb.getInt(RETENTION_KEY, DEFAULT_RETENTION));
            for (DriveClient.Entry entry : runs) {
                if (doomed.contains(entry.name)) {
                    Log.i(TAG, "BackupRunner: dropping old run " + entry.name);
                    drive.delete(entry.id);
                }
            }
        } catch (Exception e) {
            // A failed trim leaves too many backups, which is the harmless way
            // for this to go wrong. It must never fail the run that succeeded.
            Log.e(TAG, "BackupRunner: trim failed", e);
        }
    }
}
