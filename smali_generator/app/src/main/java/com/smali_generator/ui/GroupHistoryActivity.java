package com.smali_generator.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.smali_generator.abprops.AbPropStore;
import com.smali_generator.db.PatchDb;
import com.smali_generator.patches.AbProps;
import com.smali_generator.patches.GroupHistorySharing;

import java.util.Map;

/**
 * How much of a group's history goes to somebody you add, and comes back.
 *
 * Four numbers, which are four of the app's own A/B properties -- see
 * {@link GroupHistorySharing} for which and why. This screen exists rather than
 * leaving them to the A/B property list because that list is 20,000 numbers with
 * no names, and because two of these do nothing without the other two: the
 * sender's window filters what the sender's count already took, so a screen that
 * let one be raised alone would mostly look broken.
 *
 * Every row shows both what is in force and what the app answers of its own
 * accord, and every setting starts at "leave it to the app". That is not
 * decoration: the test account's server had already widened the send window to 30
 * days where the build ships 14, so a screen that had defaulted to the shipped
 * number would have been shortening the window while saying it changed nothing.
 *
 * Built in code, like the rest of the patcher's screens: the module's resource
 * table is not merged into WhatsApp's, so there is no layout to inflate.
 */
public class GroupHistoryActivity extends Activity {

    private static final int SIDE_PADDING_DP = 20;

    /** What the app itself last answered for each property, so a row can show it
     *  beside the value being forced. Empty until the funnel has been hooked for
     *  a launch, which is the only way anything observes a read. */
    private Map<Integer, Object> seen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Group history");
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }
        PatchDb.init(getApplicationContext());
        AbPropStore.load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        seen = AbPropStore.observations();
        setContentView(buildContent());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private View buildContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int side = dp(SIDE_PADDING_DP);
        content.setPadding(side, side, side, side);

        content.addView(paragraph("Adding someone to a group offers to send them recent messages. "
                + "This is how many and how far back.", Palette.secondaryText(this)));

        content.addView(sectionHeader("WHEN YOU ADD SOMEONE", true));
        content.addView(row("Most messages", GroupHistorySharing.sendMax(),
                GroupHistorySharing.SEND_MAX_PROP,
                view -> pick("Most messages", GroupHistorySharing.SEND_MAX_CHOICES,
                        GroupHistorySharing.sendMax(), GroupHistorySharing.SEND_MAX_PROP,
                        GroupHistorySharing.SEND_MAX_KEY)));
        content.addView(row("Reach back", GroupHistorySharing.sendDays(),
                GroupHistorySharing.SEND_WINDOW_PROP,
                view -> pick("Reach back", GroupHistorySharing.SEND_DAY_CHOICES,
                        GroupHistorySharing.sendDays(), GroupHistorySharing.SEND_WINDOW_PROP,
                        GroupHistorySharing.SEND_DAYS_KEY)));
        content.addView(paragraph("The app takes the newest messages first and only then drops the "
                + "ones outside the window, so reaching further back does nothing unless the count "
                + "is raised with it.", Palette.secondaryText(this)));

        content.addView(sectionHeader("WHEN SOMEONE ADDS YOU", false));
        content.addView(row("Accept at most", GroupHistorySharing.acceptMax(),
                GroupHistorySharing.ACCEPT_MAX_PROP,
                view -> pick("Accept at most", GroupHistorySharing.ACCEPT_MAX_CHOICES,
                        GroupHistorySharing.acceptMax(), GroupHistorySharing.ACCEPT_MAX_PROP,
                        GroupHistorySharing.ACCEPT_MAX_KEY)));
        content.addView(row("Accept as far back as", GroupHistorySharing.acceptDays(),
                GroupHistorySharing.ACCEPT_HALF_WINDOW_PROP,
                view -> pick("Accept as far back as", GroupHistorySharing.ACCEPT_DAY_CHOICES,
                        GroupHistorySharing.acceptDays(), GroupHistorySharing.ACCEPT_HALF_WINDOW_PROP,
                        GroupHistorySharing.ACCEPT_DAYS_KEY)));

        content.addView(paragraph("The other side decides what it keeps, using its own copy of these "
                + "two, and drops the rest without telling either of you. WhatsApp ships them at 100 "
                + "messages and 28 days, but raises them per account: history 88 days old has been sent "
                + "to an ordinary phone and read there in full. Raise these if a bundle you are sent "
                + "arrives short.", Palette.secondaryText(this)));

        if (!AbProps.funnelHooked()) {
            content.addView(paragraph("Not in force for this launch. Turn “Send more group "
                    + "history” on and restart WhatsApp; until then these values change nothing.",
                    Palette.ACCENT));
            content.addView(restartButton());
        }

        ScrollView scroller = new ScrollView(this);
        scroller.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        insetBelowSystemBars(scroller);
        return scroller;
    }

    private TextView paragraph(String text, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        view.setTextColor(color);
        view.setPadding(0, dp(6), 0, dp(10));
        return view;
    }

    private View sectionHeader(String text, boolean first) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.10f);
        title.setTextColor(Palette.ACCENT);
        title.setPadding(0, dp(first ? 8 : 22), 0, dp(4));
        return title;
    }

    /**
     * One setting: its name, what is in force, and what the app says of its own
     * accord.
     *
     * Both lines, always. A setting left alone has to read as the app's value and
     * not as a number this screen picked, and a setting that is forcing one has to
     * show what it is overriding -- that is the difference between widening the
     * window and narrowing it without noticing.
     */
    private View row(String title, int setting, int prop, View.OnClickListener onClick) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(10), 0, dp(10));
        block.setBackground(Palette.rowRipple(this));
        block.setOnClickListener(onClick);

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        name.setTextColor(Palette.primaryText(this));
        line.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chosen = new TextView(this);
        chosen.setText(label(prop, setting) + "  ›");
        chosen.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        chosen.setTypeface(Typeface.DEFAULT_BOLD);
        chosen.setTextColor(Palette.ACCENT);
        line.addView(chosen);
        block.addView(line);

        TextView detail = new TextView(this);
        detail.setText(setting == GroupHistorySharing.LEAVE_ALONE
                ? appValue(prop) : "the app's own: " + appValue(prop));
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        detail.setTextColor(Palette.secondaryText(this));
        block.addView(detail);
        return block;
    }

    /** A setting as the row states it, which for "leave it alone" is the app's own
     *  value rather than a number of this screen's choosing. */
    private String label(int prop, int setting) {
        if (setting == GroupHistorySharing.LEAVE_ALONE) {
            return "The app's own";
        }
        return isWindow(prop) ? days(setting) : setting + " messages";
    }

    private boolean isWindow(int prop) {
        return prop == GroupHistorySharing.SEND_WINDOW_PROP
                || prop == GroupHistorySharing.ACCEPT_HALF_WINDOW_PROP;
    }

    /**
     * What the app answered for this property before any override.
     *
     * Says when it does not know rather than falling silent: a property this
     * account has not been seen to read is exactly where the shipped number is a
     * guess, and a row that quietly showed the guess as the app's own is the bug
     * this screen's defaults exist to avoid.
     */
    private String appValue(int prop) {
        Object live = seen == null ? null : seen.get(prop);
        if (prop == GroupHistorySharing.SEND_WINDOW_PROP) {
            return live == null
                    ? "not read yet · ships as " + days(GroupHistorySharing.SHIPPED_SEND_DAYS)
                    : days(asSeconds(live) / 86400);
        }
        if (prop == GroupHistorySharing.ACCEPT_HALF_WINDOW_PROP) {
            return live == null
                    ? "not read yet · ships as " + days(GroupHistorySharing.SHIPPED_ACCEPT_DAYS)
                    : days(GroupHistorySharing.acceptDaysFor(asSeconds(live)));
        }
        int shipped = prop == GroupHistorySharing.SEND_MAX_PROP
                ? GroupHistorySharing.SHIPPED_SEND_MAX : GroupHistorySharing.SHIPPED_ACCEPT_MAX;
        return live == null ? "not read yet · ships as " + shipped + " messages"
                : live + " messages";
    }

    private long asSeconds(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private String days(long count) {
        return count == 1 ? "1 day" : count + " days";
    }

    /**
     * Sets one of the four, in a dialog of the values worth choosing.
     *
     * Free text would be worse than a list here: these are not independent
     * numbers -- "leave it to the app" has to stay one of the choices whatever the
     * app currently says, and a receive window has to be a whole number of days
     * twice over to survive the halving.
     */
    private void pick(String title, int[] choices, int current, int prop, String key) {
        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        int side = dp(SIDE_PADDING_DP);
        frame.setPadding(side, dp(8), side, 0);

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        for (int index = 0; index < choices.length; index++) {
            RadioButton option = new RadioButton(this);
            option.setId(index + 1);
            option.setText(choices[index] == GroupHistorySharing.LEAVE_ALONE
                    ? "Leave it to the app (" + appValue(prop) + ")"
                    : isWindow(prop) ? days(choices[index]) : choices[index] + " messages");
            option.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            option.setTextColor(Palette.primaryText(this));
            option.setPadding(dp(8), dp(6), 0, dp(6));
            Palette.paintCheckable(option);
            group.addView(option);
            if (choices[index] == current) {
                group.check(index + 1);
            }
        }
        frame.addView(group);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(frame)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Set", (ignored, which) -> {
                    int checked = group.getCheckedRadioButtonId() - 1;
                    if (checked >= 0 && checked < choices.length) {
                        PatchDb.setInt(key, choices[checked]);
                        // Published straight away: the app reads these when the
                        // dialog is put up, not at startup, so an edit made now is
                        // in force for the next person somebody adds.
                        GroupHistorySharing.publish();
                    }
                    setContentView(buildContent());
                })
                .create();
        dialog.show();
        paintDialog(dialog);
    }

    /** The framework paints its dialog buttons in the device's accent; the rest
     *  of these screens is WhatsApp's green. */
    private void paintDialog(AlertDialog dialog) {
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setTextColor(Palette.ACCENT);
        }
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (negative != null) {
            negative.setTextColor(Palette.secondaryText(this));
        }
    }

    private View restartButton() {
        Button button = Restart.button(this, "Restart WhatsApp");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        button.setLayoutParams(params);
        return button;
    }

    private int dp(int dp) {
        return Palette.dp(this, dp);
    }

    private void insetBelowSystemBars(View content) {
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }
}
