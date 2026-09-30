package com.smali_generator.backup;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Bringing a run back down.
 *
 * Both halves refuse rather than guess. A wrong files/key makes every local
 * backup on the device unreadable, which is worse than not restoring at all --
 * so the passphrase is verified before anything is written, and an existing key
 * is never replaced without the caller saying so explicitly.
 */
public final class RestoreRunner {

    private static final String TAG = "PATCH";

    public enum Readable {
        /** A wrapped key is in Drive and this device knows the passphrase. */
        YES,
        /** The run was made with no passphrase set; the database is all there is. */
        NO_KEY,
        /** A key is there, but it was wrapped under a passphrase this device no longer holds. */
        UNKNOWN_PASSPHRASE
    }

    private RestoreRunner() {
    }

    /**
     * Whether the wrapped key in a run can be opened on this device.
     *
     * Decided per run, from the verifier the run recorded, not from "does this
     * device have a passphrase": changing the passphrase does not re-wrap the
     * runs already in Drive, and a screen that still called them readable would
     * be promising an archive the user does not have. A run that did not record
     * a verifier is unknown, and unknown is not yes.
     */
    public static Readable readability(boolean keyInDrive, String runVerifier,
                                       String deviceVerifier) {
        if (!keyInDrive) {
            return Readable.NO_KEY;
        }
        if (runVerifier == null || deviceVerifier == null || !runVerifier.equals(deviceVerifier)) {
            return Readable.UNKNOWN_PASSPHRASE;
        }
        return Readable.YES;
    }

    /**
     * A file name from Drive's listing, or null if it is not one.
     *
     * The name is whatever the file is called in Drive, which the user can
     * change, and it is joined onto a shared-storage path -- so a separator in
     * it would write outside the folder this feature owns.
     */
    public static String safeName(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\u0000') >= 0) {
            return null;
        }
        return name;
    }

    /**
     * Whether this device already has a key file.
     *
     * The screen asks before it offers to replace one. restoreKey refuses an
     * existing key on its own, but that refusal is a backstop: a device that
     * can reach the restore screen has registered a number and therefore
     * always has a key, so without an explicit confirmation in front of it the
     * overwrite branch is unreachable and the button never does anything.
     */
    public static boolean hasKey(Context context) {
        return new File(context.getFilesDir(), "key").isFile();
    }

    /**
     * Makes sure the folder a restore writes into exists.
     *
     * Uninstalling WhatsApp deletes /sdcard/Android/media/com.whatsapp outright,
     * and a fresh install does not put Databases/ back until WhatsApp writes a
     * backup of its own -- which needs a registered account. So the one moment
     * this feature exists for is exactly the moment the folder is missing, and
     * opening a stream inside it fails with ENOENT.
     */
    static boolean ensureDirectory(File dir) {
        return dir.isDirectory() || dir.mkdirs();
    }

    /**
     * Blocks. Returns the outcome to show the user.
     *
     * The download lands beside the target and is checked against the manifest
     * before it replaces anything. The old local backup is the user's only
     * other copy, and a half-downloaded file that reported success would be
     * discovered at the one moment they depend on it.
     */
    public static String restoreDatabase(Context context, String fileId, String fileName,
                                         String expectedSha) {
        String name = safeName(fileName);
        if (name == null) {
            Log.e(TAG, "RestoreRunner: refusing a backup named " + fileName);
            return "That backup's name is not one this can write. Nothing was changed.";
        }
        if (!ensureDirectory(new File(BackupRunner.DATABASES_DIR))) {
            Log.e(TAG, "RestoreRunner: cannot create " + BackupRunner.DATABASES_DIR);
            return "Could not create WhatsApp's backup folder. Nothing was changed.";
        }
        GoogleAuth.Token token = GoogleAuth.token(context);
        if (token.value == null) {
            return "Google Drive needs to be reconnected";
        }
        File target = new File(BackupRunner.DATABASES_DIR, name);
        File staging = new File(BackupRunner.DATABASES_DIR, name + ".restoring");
        if (!new DriveClient(context, token.value).downloadTo(fileId, staging)) {
            return "Download failed. Nothing was changed.";
        }
        if (expectedSha != null && !expectedSha.equals(DriveClient.sha256(staging))) {
            staging.delete();
            Log.e(TAG, "RestoreRunner: downloaded copy does not match the manifest");
            return "The downloaded backup does not match its manifest. Nothing was changed.";
        }
        target.delete();
        if (!staging.renameTo(target)) {
            staging.delete();
            return "Could not put the backup in place. Nothing was changed.";
        }
        Log.i(TAG, "RestoreRunner: wrote " + target + ", verified=" + (expectedSha != null));
        return "Restored to " + target.getName()
                + ". WhatsApp reads it only during setup, so reinstall and verify your number.";
    }

    /**
     * Writes files/key from a wrapped blob. Refuses an existing key unless the
     * caller has confirmed: replacing a live key orphans every local backup.
     */
    public static String restoreKey(Context context, String blobFileId, String passphrase,
                                    boolean overwrite) {
        File target = new File(context.getFilesDir(), "key");
        if (target.exists() && !overwrite) {
            return "This device already has a key. Replacing it makes every local backup here "
                    + "unreadable -- confirm if that is what you want.";
        }
        GoogleAuth.Token token = GoogleAuth.token(context);
        if (token.value == null) {
            return "Google Drive needs to be reconnected";
        }
        byte[] blob = new DriveClient(context, token.value).download(blobFileId);
        if (blob == null) {
            return "Download failed";
        }
        byte[] plain;
        try {
            plain = KeyVault.unwrap(blob, passphrase);
        } catch (KeyVault.BadBlob e) {
            // Nothing has been written at this point, which is the whole reason
            // the unwrap happens before the file is touched.
            return "Wrong passphrase, or that key file is damaged. Nothing was changed.";
        }
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(plain);
        } catch (Exception e) {
            Log.e(TAG, "RestoreRunner: cannot write the key", e);
            return "Could not write the key file";
        }
        Log.i(TAG, "RestoreRunner: key restored, " + plain.length + " bytes");
        return "Key restored";
    }
}
