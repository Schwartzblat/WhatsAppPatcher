package com.smali_generator.patches;

import android.util.Log;

import com.smali_generator.Hook;
import com.smali_generator.HookCategory;
import com.smali_generator.backup.BackupJob;
import com.smali_generator.backup.BackupRunner;
import com.smali_generator.backup.GoogleAuth;
import com.smali_generator.db.PatchDb;
import com.smali_generator.ui.DriveBackupActivity;
import com.smali_generator.utils.Utils;

/**
 * Copies WhatsApp's local chat backup to the user's own Google Drive.
 *
 * WhatsApp's built-in Drive backup cannot work in a re-signed build: it calls
 * backup.googleapis.com/v1/clients/wa, a restricted Google service bound to
 * WhatsApp's own OAuth client, and no project a user owns can be bound to it.
 * See docs/superpowers/specs/2026-09-30-drive-backup-design.md.
 *
 * Unusually for this patch, nothing here is hooked: no ArtHooks redirect, no
 * finder, no substituted name. It reads getFilesDir(), a literal filename, and
 * shared storage -- none of which obfuscation can move -- so it is the one
 * feature that cannot break on a WhatsApp update.
 */
public class DriveBackup implements Hook {

    private static final String TAG = "PATCH";

    public static final String CHARGING_KEY = "drive_backup_requires_charging";

    public String id() {
        return "drive_backup";
    }

    public String title() {
        return "Back up chats to Google Drive";
    }

    public String description() {
        return "WhatsApp's own Drive backup cannot work in a patched build. This sends the "
                + "same local backup to a folder in your Drive instead.";
    }

    public HookCategory category() {
        return HookCategory.OTHER;
    }

    public boolean defaultEnabled() {
        // Off by default: it needs a Google account and an OAuth client the
        // user has to register, so an untouched install has nothing to send.
        return false;
    }

    public Class<?> configScreen() {
        return DriveBackupActivity.class;
    }

    public String configSummary() {
        String account = GoogleAuth.accountName();
        return summary(account != null && !account.isEmpty(),
                PatchDb.getString(BackupRunner.PASSPHRASE_VERIFIER_KEY, null) != null,
                PatchDb.getString(BackupRunner.LAST_RESULT_KEY, null));
    }

    /**
     * Pure so it can be tested, and so the screen and the settings row cannot
     * disagree about what state the feature is in.
     */
    public static String summary(boolean connected, boolean hasPassphrase, String lastResult) {
        if (!connected) {
            return "Not connected to Google Drive";
        }
        if (!hasPassphrase) {
            return "On, but with no passphrase set -- the database is sent without its key";
        }
        if (lastResult == null || lastResult.isEmpty()) {
            return "On, has not run yet";
        }
        return lastResult;
    }

    public void load() {
        // No I/O and no network here: load() runs during app startup, and the
        // repo rule is that it must not throw. Scheduling is cheap and
        // idempotent; the work happens in the job.
        BackupJob.schedule(Utils.getApplicationContext(), PatchDb.getFlag(CHARGING_KEY, false));
        Log.i(TAG, "DriveBackup: loaded, " + configSummary());
    }

    public void unload() {
        Log.i(TAG, "DriveBackup: unloaded");
    }
}
