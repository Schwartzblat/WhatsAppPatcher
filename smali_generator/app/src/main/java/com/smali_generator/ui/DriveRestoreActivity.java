package com.smali_generator.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Insets;
import android.os.Bundle;
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
import com.smali_generator.backup.BackupRunner;
import com.smali_generator.backup.DriveClient;
import com.smali_generator.backup.GoogleAuth;
import com.smali_generator.backup.RestoreRunner;
import com.smali_generator.db.PatchDb;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Lists what is in Drive and brings one run back.
 *
 * This screen is reached through WhatsApp's own settings, which cannot be
 * opened before a number is registered -- so it serves a device already set up,
 * not the new phone. The new-phone path is the manual one in CLAUDE.md: put the
 * file in /WhatsApp/Databases/ and let WhatsApp's setup offer the restore.
 */
public class DriveRestoreActivity extends Activity {

    private LinearLayout content;

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
        setTitle("Restore from Drive");
        content.addView(line("Loading..."));
        new Thread(this::loadRuns, "drive-restore-list").start();
    }

    private void loadRuns() {
        GoogleAuth.Token token = GoogleAuth.token(getApplicationContext());
        if (token.value == null) {
            runOnUiThread(() -> {
                content.removeAllViews();
                content.addView(line("Connect Google Drive on the previous screen first."));
            });
            return;
        }
        DriveClient drive = new DriveClient(getApplicationContext(), token.value);
        String root = drive.folderId(BackupRunner.ROOT_FOLDER, null);
        List<DriveClient.Entry> runs =
                root == null ? java.util.Collections.emptyList() : drive.children(root);
        runOnUiThread(() -> {
            content.removeAllViews();
            content.addView(line("Reached only after your number is registered. On a new phone, "
                    + "download a backup from Drive by hand into /WhatsApp/Databases/ before "
                    + "setup instead."));
            if (runs.isEmpty()) {
                content.addView(line("No backups in Drive yet."));
                return;
            }
            for (DriveClient.Entry run : runs) {
                content.addView(row(run.name, v -> openRun(drive, run)));
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
