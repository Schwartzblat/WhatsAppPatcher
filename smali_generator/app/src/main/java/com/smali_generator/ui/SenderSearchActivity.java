package com.smali_generator.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.smali_generator.db.PatchDb;
import com.smali_generator.db.SenderJids;
import com.smali_generator.patches.SenderSearch;

/**
 * How wide a net the sender search casts.
 *
 * Fixed choices rather than free text: every one of these is a number with a
 * sensible range and a cost to getting wrong, and a picker cannot be typed
 * into wrongly. Leaving the screen drops the cached contact list, so a contact
 * saved today is searchable without a restart.
 */
public class SenderSearchActivity extends Activity {

    private static final int[] DIGIT_CHOICES = {3, 4, 5, 6, 7};
    private static final int[] NAME_CHOICES = {2, 3, 4, 5};
    private static final int[] SENDER_CHOICES = {16, 32, 64, 128, 256};

    private LinearLayout rows;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Find messages by sender");

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        int pad = Palette.dp(this, 8);
        rows.setPadding(0, pad, 0, pad);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(rows);
        setContentView(scroll);

        draw();
    }

    @Override
    protected void onDestroy() {
        // The contact list is cached for the life of the process; a number
        // saved while this screen was open would otherwise not be findable
        // until the next launch.
        SenderJids.invalidate();
        super.onDestroy();
    }

    private void draw() {
        rows.removeAllViews();
        rows.addView(row("Shortest number searched",
                "A search for fewer digits than this is left alone.",
                SenderSearch.MIN_DIGITS_KEY, SenderSearch.DEFAULT_MIN_DIGITS, DIGIT_CHOICES, " digits"));
        rows.addView(row("Shortest name searched",
                "A search for fewer letters than this is left alone.",
                SenderSearch.MIN_NAME_KEY, SenderSearch.DEFAULT_MIN_NAME, NAME_CHOICES, " letters"));
        rows.addView(row("Most people per search",
                "A search matching more than this uses the closest matches.",
                SenderSearch.MAX_SENDERS_KEY, SenderSearch.DEFAULT_MAX_SENDERS, SENDER_CHOICES, " people"));
    }

    private View row(String title, String caption, final String key, final int fallback,
                     final int[] choices, final String unit) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        int side = Palette.dp(this, 20);
        int vertical = Palette.dp(this, 14);
        row.setPadding(side, vertical, side, vertical);
        row.setBackground(Palette.rowRipple(this));
        row.setClickable(true);

        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(16);
        heading.setTextColor(Palette.primaryText(this));
        row.addView(heading);

        final TextView value = new TextView(this);
        value.setText(PatchDb.getInt(key, fallback) + unit + " — " + caption);
        value.setTextSize(13);
        value.setTextColor(Palette.secondaryText(this));
        value.setPadding(0, Palette.dp(this, 4), 0, 0);
        row.addView(value);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View clicked) {
                pick(title, key, fallback, choices, unit, value, caption);
            }
        });
        return row;
    }

    private void pick(String title, final String key, int fallback, final int[] choices,
                      final String unit, final TextView value, final String caption) {
        final CharSequence[] labels = new CharSequence[choices.length];
        int checked = -1;
        int current = PatchDb.getInt(key, fallback);
        for (int i = 0; i < choices.length; i++) {
            labels[i] = choices[i] + unit;
            if (choices[i] == current) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        PatchDb.setInt(key, choices[which]);
                        value.setText(choices[which] + unit + " — " + caption);
                        dialog.dismiss();
                    }
                })
                .show();
    }
}
