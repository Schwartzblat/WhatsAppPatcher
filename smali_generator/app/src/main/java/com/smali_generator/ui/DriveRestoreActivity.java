package com.smali_generator.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Insets;
import android.os.Bundle;
import android.util.Log;
import android.text.format.DateUtils;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.smali_generator.backup.BackupManifest;
import com.smali_generator.BootHealth;
import com.smali_generator.backup.BackupRunner;
import com.smali_generator.backup.Crypt14;
import com.smali_generator.backup.Crypt14Restore;
import com.smali_generator.backup.DatabaseCheck;
import com.smali_generator.backup.DriveClient;
import com.smali_generator.backup.GoogleAuth;
import com.smali_generator.backup.LocalBackups;
import com.smali_generator.backup.PendingRestore;
import com.smali_generator.backup.RestoreRunner;
import com.smali_generator.backup.RestoreSwap;
import com.smali_generator.db.PatchDb;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Restores a backup: from WhatsApp's own folder on this phone (no network
 * needed), or one run from Drive.
 *
 * This screen is reached through WhatsApp's own settings, which cannot be
 * opened before a number is registered -- so it serves a device already set up,
 * not the new phone. The new-phone path is the manual one in CLAUDE.md: put the
 * file in /WhatsApp/Databases/ and let WhatsApp's setup offer the restore.
 */
public class DriveRestoreActivity extends Activity {

    private static final String TAG = "PATCH";

    private LinearLayout content;
    /** Filled off the main thread: listing /sdcard is a stat per file. */
    private LinearLayout localBox;
    /** The Drive half, kept apart so its reload cannot wipe the local sections. */
    private LinearLayout driveBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroller = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Palette.dp(this, 20);
        content.setPadding(pad, pad, pad, pad);
        scroller.addView(content);
        insetBelowSystemBars(scroller);
        setContentView(scroller);
        setTitle("Restore");
        // What startup last did to the database, which has no screen of its own:
        // the sentence that says where somebody's chat history is must reach them.
        String last = PatchDb.getString(RestoreSwap.RESULT_KEY, "");
        if (last != null && !last.isEmpty()) {
            content.addView(line("Last restore step: " + last));
        }
        // Local first and independent of the network: a local restore needs
        // neither Drive nor a token, so it must not wait on them.
        localBox = new LinearLayout(this);
        localBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(localBox);
        addLocalSection();
        addUndoSection();
        driveBox = new LinearLayout(this);
        driveBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(driveBox);
        driveBox.addView(line("Loading Drive..."));
        new Thread(this::loadRuns, "drive-restore-list").start();
    }

    private void addLocalSection() {
        localBox.addView(line("On this phone"));
        final TextView loading = line("Looking for backup files\u2026");
        localBox.addView(loading);
        new Thread(() -> {
            List<LocalBackups.Entry> local;
            try {
                local = LocalBackups.list(new File(BackupRunner.DATABASES_DIR));
            } catch (Throwable t) {
                Log.e(TAG, "DriveRestoreActivity: could not list local backups", t);
                local = java.util.Collections.emptyList();
            }
            final List<LocalBackups.Entry> found = local;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                localBox.removeView(loading);
                if (found.isEmpty()) {
                    localBox.addView(line("No backup files in WhatsApp's own folder."));
                    return;
                }
                for (final LocalBackups.Entry entry : found) {
                    String when = DateUtils.getRelativeTimeSpanString(entry.modifiedAtMs).toString();
                    localBox.addView(row(entry.name + "\n" + (entry.bytes / (1024 * 1024)) + " MB, "
                            + when, v -> confirmLocalRestore(entry)));
                }
            });
        }, "local-restore-list").start();
    }

    private void confirmLocalRestore(final LocalBackups.Entry entry) {
        // A second restore keeps nothing of the database it replaces: the copy
        // for Undo is only ever written by the first one. The sentence has to
        // say which of the two this is.
        boolean again = RestoreSwap.hasUndo(getApplicationContext().getDatabasePath("msgstore.db"));
        String kept = again
                ? "Your current messages will NOT be kept. The copy kept for Undo is still the"
                + " one from before your first restore, so undoing goes back to that, not to"
                + " what is on this phone now."
                : "Your current messages are kept, so you can undo it.";
        new AlertDialog.Builder(this)
                .setTitle("Restore this backup?")
                .setMessage("This replaces the messages on this phone with the ones in "
                        + entry.name + ". " + kept
                        + "\n\nNothing changes until you restart WhatsApp.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore", (d, which) -> stageLocalRestore(entry))
                .show();
    }

    private void stageLocalRestore(final LocalBackups.Entry entry) {
        if (!PendingRestore.beginPreparing()) {
            new AlertDialog.Builder(this)
                    .setTitle("Restore")
                    .setMessage("A restore is already being prepared. Nothing was changed.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        final AlertDialog working = new AlertDialog.Builder(this)
                .setMessage("Decrypting and checking " + entry.name + "\u2026")
                .setCancelable(false)
                .show();
        new Thread(() -> {
            String result;
            try {
                result = stageAndVerify(entry);
            } catch (Throwable t) {
                // An Error escaping a bare thread takes the host app down, and
                // that crash is counted as a bad exit by BootHealth.
                Log.e(TAG, "DriveRestoreActivity: staging failed", t);
                result = "The restore could not be prepared: " + t + ". Nothing was changed.";
            } finally {
                PendingRestore.endPreparing();
            }
            final String outcome = result;
            runOnUiThread(() -> {
                // The staged file and the pending record are the work and they
                // outlive the screen, so a dead activity just has nowhere to
                // say so; touching its windows would throw.
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                working.dismiss();
                // A dialog, not a toast: a restore that is staged but never
                // committed looks exactly like one that did nothing.
                new AlertDialog.Builder(this)
                        .setTitle("Restore")
                        .setMessage(outcome)
                        .setPositiveButton("OK", null)
                        .show();
            });
        }, "local-restore").start();
    }

    /** Returns the sentence to show. Runs off the main thread. */
    private String stageAndVerify(LocalBackups.Entry entry) {
        Context context = getApplicationContext();
        File target = context.getDatabasePath("msgstore.db");
        String refusal = PendingRestore.stagingRefusal(BootHealth.inSafeMode(),
                PatchDb.getString(RestoreSwap.PENDING_UNDO_KEY, "").length() > 0);
        if (refusal != null) {
            return refusal;
        }
        // WhatsApp rewrites this folder nightly, so the entry is a snapshot:
        // size and existence are read again here, at the moment of use.
        long length = entry.file.length();
        if (!entry.file.isFile() || length <= 0) {
            return "That backup file is gone or empty now. Nothing was changed.";
        }
        File parent = target.getParentFile();
        long needed = Crypt14Restore.requiredFreeBytes(length);
        if (parent == null || parent.getUsableSpace() < needed) {
            return "Not enough room to unpack that backup: it needs about "
                    + (needed / (1024 * 1024)) + " MB free. Nothing was changed.";
        }
        byte[] sniff = new byte[Crypt14.SNIFF_BYTES];
        int read = 0;
        try (java.io.InputStream in = new java.io.FileInputStream(entry.file)) {
            while (read < sniff.length) {
                int r = in.read(sniff, read, sniff.length - read);
                if (r < 0) {
                    break;
                }
                read += r;
            }
        } catch (java.io.IOException e) {
            return "Could not read that backup: " + e.getMessage();
        }
        if (read < sniff.length) {
            sniff = java.util.Arrays.copyOf(sniff, read);
        }
        Crypt14.Header header = Crypt14.parseHeader(sniff);
        if (header == null) {
            return "That file is not a crypt14 backup this version understands."
                    + " Nothing was changed.";
        }
        String appVersion = null;
        try {
            appVersion = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            // Without the app's own version the gate cannot be applied; the
            // comparison below treats that as "allow" deliberately.
        }
        if (!Crypt14.isNotNewerThan(header.waVersion, appVersion)) {
            return "That backup was made by WhatsApp " + header.waVersion
                    + " and this is " + appVersion + "."
                    + " A newer backup cannot be opened by an older WhatsApp,"
                    + " so nothing was changed.";
        }
        byte[] key = Crypt14.databaseKey(new File(context.getFilesDir(), "key"));
        if (key == null) {
            return "WhatsApp's database key is not on this phone yet."
                    + " It appears once this number is registered. Nothing was changed.";
        }
        File staged = RestoreSwap.stagedFile(target);
        Crypt14Restore.Outcome decrypted = Crypt14Restore.stage(entry.file, key, staged);
        if (!decrypted.ok) {
            return decrypted.message;
        }
        DatabaseCheck.Report report = DatabaseCheck.inspect(staged);
        if (!report.usable) {
            staged.delete();
            return report.reason + " Nothing was changed.";
        }
        if (!PendingRestore.record(staged, target)) {
            staged.delete();
            return "An undo was queued while this was being prepared, so the restore was"
                    + " dropped. Restart WhatsApp first. Nothing was changed.";
        }
        return report.reason + "\n\nNow close WhatsApp completely and open it again."
                + " The restore is applied as it starts, and nothing changes until then.";
    }

    private void addUndoSection() {
        File target = getApplicationContext().getDatabasePath("msgstore.db");
        if (!RestoreSwap.hasUndo(target)) {
            return;
        }
        content.addView(line("Undo"));
        content.addView(row("Put back the messages from before the first restore",
                v -> confirmUndo()));
    }

    /**
     * Records the undo; startup performs it. WhatsApp holds msgstore.db open
     * while it runs, so moving files now would succeed on Linux and leave it
     * writing into inodes no longer at those paths -- and SQLite would then
     * create a -wal by path beside whichever database sits there.
     */
    private void confirmUndo() {
        new AlertDialog.Builder(this)
                .setTitle("Undo the restore?")
                .setMessage("This puts back the messages that were on this phone before the"
                        + " first restore. Anything received since is not kept."
                        + "\n\nClose WhatsApp completely and open it again;"
                        + " nothing changes until then.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Undo", (d, which) -> {
                    String message = PendingRestore.undoRefusal(BootHealth.inSafeMode(),
                            PatchDb.getString(RestoreSwap.PENDING_STAGED_KEY, "").length() > 0);
                    if (message == null) {
                        PendingRestore.recordUndo(
                                getApplicationContext().getDatabasePath("msgstore.db"));
                        message = "Undo recorded.\n\nNow close WhatsApp completely and open it"
                                + " again. The undo is applied as it starts.";
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("Undo")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show();
                })
                .show();
    }

    private void loadRuns() {
        GoogleAuth.Token token = GoogleAuth.token(getApplicationContext());
        if (token.value == null) {
            runOnUiThread(() -> {
                driveBox.removeAllViews();
                driveBox.addView(line("Connect Google Drive on the previous screen first."));
            });
            return;
        }
        DriveClient drive = new DriveClient(getApplicationContext(), token.value);
        String root = drive.folderId(BackupRunner.ROOT_FOLDER, null);
        List<DriveClient.Entry> runs =
                root == null ? java.util.Collections.emptyList() : drive.children(root);
        runOnUiThread(() -> {
            driveBox.removeAllViews();
            driveBox.addView(line("Reached only after your number is registered. On a new phone, "
                    + "download a backup from Drive by hand into /WhatsApp/Databases/ before "
                    + "setup instead."));
            if (runs.isEmpty()) {
                driveBox.addView(line("No backups in Drive yet."));
                return;
            }
            for (DriveClient.Entry run : runs) {
                driveBox.addView(row(run.name, v -> openRun(drive, run)));
            }
        });
    }

    private void openRun(DriveClient drive, DriveClient.Entry run) {
        new Thread(() -> {
            List<DriveClient.Entry> files = drive.children(run.id);
            DriveClient.Entry db = null;
            DriveClient.Entry keyBlob = null;
            DriveClient.Entry manifestFile = null;
            for (DriveClient.Entry f : files) {
                if (f.name.equals(BackupRunner.KEY_BLOB_NAME)) {
                    keyBlob = f;
                } else if (f.name.equals(BackupManifest.FILE_NAME)) {
                    manifestFile = f;
                } else if (BackupRunner.cryptOf(f.name) != null) {
                    db = f;
                }
            }
            final DriveClient.Entry database = db;
            final DriveClient.Entry key = keyBlob;
            // The manifest is what makes a restore checkable: it carries the
            // database's digest and the verifier of the passphrase this run's
            // key was wrapped under. Uploading it and never reading it back
            // would leave both questions unanswerable.
            BackupManifest parsed = null;
            if (manifestFile != null) {
                byte[] raw = drive.download(manifestFile.id);
                if (raw != null) {
                    parsed = BackupManifest.parse(new String(raw, StandardCharsets.UTF_8));
                }
            }
            final BackupManifest manifest = parsed;
            RestoreRunner.Readable readable = RestoreRunner.readability(key != null,
                    manifest == null ? null : manifest.passVerifier,
                    PatchDb.getString(BackupRunner.PASSPHRASE_VERIFIER_KEY, null));
            runOnUiThread(() -> showRun(run, database, key, readable, manifest));
        }, "drive-restore-open").start();
    }

    private void showRun(DriveClient.Entry run, DriveClient.Entry database,
                         DriveClient.Entry key, RestoreRunner.Readable readable,
                         BackupManifest manifest) {
        String note;
        switch (readable) {
            case YES:
                note = "The key for this run is in Drive and this device knows the passphrase.";
                break;
            case NO_KEY:
                note = "No key was uploaded with this run -- WhatsApp can restore it, but it "
                        + "cannot be decrypted on its own.";
                break;
            default:
                // Covers both "the passphrase was changed since this run" and
                // "this run does not say which passphrase it used". Neither is
                // a promise the screen is entitled to make.
                note = "A key is in Drive, but this device cannot show that it was wrapped under "
                        + "the passphrase it has now. Restoring it needs the passphrase this run "
                        + "was made with.";
        }
        if (manifest == null) {
            note += "\n\nThis run has no manifest, so a restored copy cannot be checked "
                    + "against it.";
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(run.name)
                .setMessage(note)
                .setNegativeButton("Close", null);
        if (database != null) {
            builder.setPositiveButton("Restore database", (d, w) -> new Thread(() -> {
                String outcome = RestoreRunner.restoreDatabase(getApplicationContext(),
                        database.id, database.name,
                        manifest == null ? null : manifest.dbSha256);
                runOnUiThread(() -> Toast.makeText(this, outcome, Toast.LENGTH_LONG).show());
            }, "drive-restore-db").start());
        }
        if (key != null) {
            builder.setNeutralButton("Restore key", (d, w) -> askPassphraseThenRestoreKey(key));
        }
        builder.show();
    }

    private void askPassphraseThenRestoreKey(DriveClient.Entry key) {
        if (RestoreRunner.hasKey(this)) {
            // The refusal inside restoreKey is the backstop; this is the
            // explicit confirmation it asks for. Every device that can open
            // this screen has a key, so without this the overwrite branch is
            // unreachable and Restore key refuses forever.
            new AlertDialog.Builder(this)
                    .setTitle("Replace this device's key?")
                    .setMessage("Every local backup already on this phone was encrypted with "
                            + "the key it has now, and replacing it makes those unreadable. The "
                            + "backup you are restoring stays readable.")
                    .setPositiveButton("Replace", (d, w) -> askPassphrase(key, true))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        askPassphrase(key, false);
    }

    private void askPassphrase(DriveClient.Entry key, boolean overwrite) {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle("Passphrase")
                .setView(field)
                .setPositiveButton("Restore", (d, w) -> new Thread(() -> {
                    String outcome = RestoreRunner.restoreKey(getApplicationContext(), key.id,
                            field.getText().toString(), overwrite);
                    runOnUiThread(() -> Toast.makeText(this, outcome, Toast.LENGTH_LONG).show());
                }, "drive-restore-key").start())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Keeps the content clear of the system bars and the action bar.
     *
     * The host app targets an SDK that forces edge-to-edge and this activity
     * inherits that, so the window runs the full height of the display and the
     * action bar is laid over the content rather than above it. Without this
     * the first two rows render underneath it -- measured on the device, the
     * summary and Connect Google Drive were simply invisible. The scroller is
     * what gets the inset, not content, whose own padding is the screen margin.
     */
    private void insetBelowSystemBars(View content) {
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    private TextView line(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(Palette.secondaryText(this));
        view.setPadding(0, Palette.dp(this, 8), 0, Palette.dp(this, 8));
        return view;
    }

    private View row(String title, View.OnClickListener onClick) {
        TextView view = new TextView(this);
        view.setText(title);
        view.setTextSize(16);
        view.setTextColor(Palette.primaryText(this));
        view.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Palette.dp(this, 14);
        view.setPadding(0, pad, 0, pad);
        view.setBackground(Palette.rowRipple(this));
        view.setOnClickListener(onClick);
        return view;
    }
}
