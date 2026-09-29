package com.smali_generator.ui;

import android.accounts.AccountManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.smali_generator.backup.BackupJob;
import com.smali_generator.backup.BackupRunner;
import com.smali_generator.backup.GoogleAuth;
import com.smali_generator.backup.KeyVault;
import com.smali_generator.backup.PassphraseHolder;
import com.smali_generator.db.PatchDb;
import com.smali_generator.patches.DriveBackup;

public class DriveBackupActivity extends Activity {

    private static final String TAG = "PATCH";
    private static final int PICK_ACCOUNT = 1;
    private static final int GRANT_CONSENT = 2;
    private static final int SIDE_PADDING_DP = 20;

    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroller = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Palette.dp(this, SIDE_PADDING_DP);
        content.setPadding(pad, pad, pad, pad);
        scroller.addView(content);
        setContentView(scroller);
        setTitle("Drive backup");
    }

    @Override
    protected void onResume() {
        super.onResume();
        redraw();
    }

    private void redraw() {
        content.removeAllViews();
        String account = GoogleAuth.accountName();
        boolean connected = account != null && !account.isEmpty();
        boolean hasPassphrase =
                PatchDb.getString(BackupRunner.PASSPHRASE_VERIFIER_KEY, null) != null;

        content.addView(paragraph(DriveBackup.summary(connected, hasPassphrase,
                PatchDb.getString(BackupRunner.LAST_RESULT_KEY, null))));

        content.addView(row(connected ? "Google account: " + account : "Connect Google Drive",
                v -> startActivityForResult(GoogleAuth.chooserIntent(), PICK_ACCOUNT)));

        content.addView(row(hasPassphrase ? "Change passphrase" : "Set a passphrase",
                v -> askForPassphrase()));

        content.addView(row("Back up now", v -> backUpNow()));

        content.addView(row(PatchDb.getFlag(DriveBackup.CHARGING_KEY, false)
                ? "Only while charging: on" : "Only while charging: off", v -> {
            boolean now = !PatchDb.getFlag(DriveBackup.CHARGING_KEY, false);
            PatchDb.setFlag(DriveBackup.CHARGING_KEY, now);
            BackupJob.schedule(getApplicationContext(), now);
            redraw();
        }));

        content.addView(row("Restore from Drive", v ->
                startActivity(new Intent(this, DriveRestoreActivity.class))));
    }

    private void askForPassphrase() {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle("Passphrase")
                .setMessage("The database key is wrapped under this before it leaves the phone. "
                        + "Lose it and the Drive copy cannot be decrypted -- WhatsApp's own "
                        + "restore still works.")
                .setView(field)
                .setPositiveButton("Save", (d, w) -> {
                    String value = field.getText().toString();
                    if (value.isEmpty()) {
                        return;
                    }
                    String salt = KeyVault.newSaltB64();
                    PatchDb.setString(BackupRunner.PASSPHRASE_SALT_KEY, salt);
                    PatchDb.setString(BackupRunner.PASSPHRASE_VERIFIER_KEY,
                            KeyVault.verifier(value, salt));
                    PassphraseHolder.set(value);
                    redraw();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void backUpNow() {
        Toast.makeText(this, "Backing up...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String outcome = BackupRunner.runOnce(getApplicationContext());
            runOnUiThread(() -> {
                Toast.makeText(this, outcome, Toast.LENGTH_LONG).show();
                redraw();
            });
        }, "drive-backup-now").start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_ACCOUNT && data != null) {
            String name = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
            if (name != null) {
                GoogleAuth.setAccountName(name);
                Log.i(TAG, "DriveBackupActivity: account chosen");
                // The first token request for a scope the user has not granted
                // comes back as an intent rather than an error; launching it is
                // the consent step.
                new Thread(() -> {
                    GoogleAuth.Token token = GoogleAuth.token(getApplicationContext());
                    if (token.consent != null) {
                        runOnUiThread(() ->
                                startActivityForResult(token.consent, GRANT_CONSENT));
                    }
                }, "drive-consent").start();
            }
        }
        redraw();
    }

    private TextView paragraph(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(Palette.secondaryText(this));
        view.setPadding(0, Palette.dp(this, 8), 0, Palette.dp(this, 16));
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
