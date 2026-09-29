package com.smali_generator.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
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
            boolean verifierSet =
                    PatchDb.getString(BackupRunner.PASSPHRASE_VERIFIER_KEY, null) != null;
            RestoreRunner.Readable readable =
                    RestoreRunner.readability(key != null, verifierSet);
            runOnUiThread(() -> showRun(run, database, key, readable));
        }, "drive-restore-open").start();
    }

    private void showRun(DriveClient.Entry run, DriveClient.Entry database,
                         DriveClient.Entry key, RestoreRunner.Readable readable) {
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
                note = "A key is in Drive, but it was wrapped under a passphrase this device no "
                        + "longer has. Restoring it needs that passphrase.";
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(run.name)
                .setMessage(note)
                .setNegativeButton("Close", null);
        if (database != null) {
            builder.setPositiveButton("Restore database", (d, w) -> new Thread(() -> {
                String outcome = RestoreRunner.restoreDatabase(
                        getApplicationContext(), database.id, database.name);
                runOnUiThread(() -> Toast.makeText(this, outcome, Toast.LENGTH_LONG).show());
            }, "drive-restore-db").start());
        }
        if (key != null) {
            builder.setNeutralButton("Restore key", (d, w) -> askPassphraseThenRestoreKey(key));
        }
        builder.show();
    }

    private void askPassphraseThenRestoreKey(DriveClient.Entry key) {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle("Passphrase")
                .setView(field)
                .setPositiveButton("Restore", (d, w) -> new Thread(() -> {
                    // overwrite=false first: the refusal is what tells the user
                    // that replacing a live key orphans their local backups.
                    String outcome = RestoreRunner.restoreKey(getApplicationContext(), key.id,
                            field.getText().toString(), false);
                    runOnUiThread(() -> Toast.makeText(this, outcome, Toast.LENGTH_LONG).show());
                }, "drive-restore-key").start())
                .setNegativeButton("Cancel", null)
                .show();
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
