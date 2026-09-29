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

    public static Readable readability(boolean keyInDrive, boolean verifierSet) {
        if (!keyInDrive) {
            return Readable.NO_KEY;
        }
        return verifierSet ? Readable.YES : Readable.UNKNOWN_PASSPHRASE;
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

    /** Blocks. Returns the outcome to show the user. */
    public static String restoreDatabase(Context context, String fileId, String fileName) {
        GoogleAuth.Token token = GoogleAuth.token(context);
        if (token.value == null) {
            return "Google Drive needs to be reconnected";
        }
        File target = new File(BackupRunner.DATABASES_DIR, fileName);
        if (!new DriveClient(context, token.value).downloadTo(fileId, target)) {
            return "Download failed";
        }
        Log.i(TAG, "RestoreRunner: wrote " + target);
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
