package com.smali_generator.ui;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.smali_generator.Hook;
import com.smali_generator.HookCategory;
import com.smali_generator.InitProvider;
import com.smali_generator.db.PatchDb;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The patcher's settings screen: one switch per hook, grouped by section.
 *
 * Built in code rather than from a layout. The module's resource table is not
 * merged into WhatsApp's, so anything inflated here would have to come from a
 * separately loaded Resources; a screen this plain is not worth that.
 *
 * Both the list and its sections are generated from {@link InitProvider#hooks}
 * and {@link HookCategory}, so a hook added later appears here, under its own
 * heading, with no change to this file.
 */
public class PatchSettingsActivity extends Activity {
    private static final String TAG = "PATCH";

    /** WhatsApp's green, so the switches read as part of the app they live in. */
    private static final int ACCENT = 0xFF25D366;

    /** The off state. A mid grey rather than a theme colour: it has to sit on
     *  both the light and the dark background this activity can be shown on. */
    private static final int NEUTRAL = 0xFF9E9E9E;

    /**
     * Text colours, picked per theme rather than left to the theme's own.
     *
     * DeviceDefault's primary text is a light grey on dark, which reads as
     * washed out against this background; these are pushed towards white, and
     * the secondary tone is a light slate rather than a dimmed copy of the
     * primary. Nothing here uses View.setAlpha on text -- dimming the view
     * dims the glyph edges too, which is most of what made it hard to read.
     */
    private static final int TEXT_PRIMARY_DARK = 0xFFFFFFFF;
    private static final int TEXT_SECONDARY_DARK = 0xFFCFD6DB;
    private static final int TEXT_PRIMARY_LIGHT = 0xFF0B141A;
    private static final int TEXT_SECONDARY_LIGHT = 0xFF3B4A54;

    /** WhatsApp puts dark text on its own green buttons; so does this one. */
    private static final int ON_ACCENT = 0xFF0B141A;

    private static final int SIDE_PADDING_DP = 20;
    private static final int ROW_PADDING_DP = 14;

    /**
     * Long enough for this process to be gone before the alarm fires, short
     * enough that the app is back before the launcher finishes animating.
     */
    private static final long RESTART_DELAY_MS = 300;

    private TextView pendingNotice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Patcher");
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }
        // Idempotent, and the screen has to read flags even in the unlikely
        // case that the provider never ran.
        PatchDb.init(getApplicationContext());
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

        pendingNotice = new TextView(this);
        pendingNotice.setText("Restart WhatsApp to apply the change.");
        pendingNotice.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        pendingNotice.setTextColor(ACCENT);
        pendingNotice.setPadding(0, 0, 0, dp(12));
        pendingNotice.setVisibility(View.GONE);
        content.addView(pendingNotice);

        boolean isFirstSection = true;
        for (HookCategory category : HookCategory.values()) {
            List<Hook> hooks = hooksIn(category);
            if (hooks.isEmpty()) {
                continue;
            }
            content.addView(sectionHeader(category, isFirstSection));
            isFirstSection = false;
            for (Hook hook : hooks) {
                content.addView(hookRow(hook));
            }
        }

        content.addView(restartButton());
        content.addView(footer());

        ScrollView scroller = new ScrollView(this);
        scroller.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        insetBelowSystemBars(scroller);
        return scroller;
    }

    private List<Hook> hooksIn(HookCategory category) {
        List<Hook> found = new ArrayList<>();
        for (Hook hook : InitProvider.hooks) {
            if (hook.category() == category) {
                found.add(hook);
            }
        }
        return found;
    }

    private View sectionHeader(HookCategory category, boolean isFirstSection) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(0, dp(isFirstSection ? 4 : 26), 0, dp(2));

        TextView title = new TextView(this);
        title.setText(category.title().toUpperCase(Locale.US));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.10f);
        title.setTextColor(ACCENT);
        header.addView(title);

        if (!category.caption().isEmpty()) {
            TextView caption = new TextView(this);
            caption.setText(category.caption());
            caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            caption.setTextColor(secondaryText());
            header.addView(caption);
        }
        return header;
    }

    private View hookRow(Hook hook) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(ROW_PADDING_DP), 0, dp(ROW_PADDING_DP));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(hook.title());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        title.setTextColor(primaryText());
        text.addView(title);

        // No "Required." here any more: the Mandatory section says it once,
        // for all of them.
        if (!hook.description().isEmpty()) {
            TextView subtitle = new TextView(this);
            subtitle.setText(hook.description());
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            subtitle.setTextColor(secondaryText());
            text.addView(subtitle);
        }
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(hook.isEnabled());
        toggle.setEnabled(hook.toggleable());
        paint(toggle);
        if (hook.toggleable()) {
            toggle.setOnCheckedChangeListener((button, checked) -> onToggled(hook, checked));
        }
        row.addView(toggle);
        return row;
    }

    /**
     * Green when on, grey when off, and a dimmed green when on but locked --
     * a mandatory hook should read as switched on, not as switched off.
     */
    private void paint(Switch toggle) {
        toggle.setThumbTintList(states(ACCENT, NEUTRAL, alpha(ACCENT, 0x8A), alpha(NEUTRAL, 0x61)));
        toggle.setTrackTintList(states(alpha(ACCENT, 0x7A), alpha(NEUTRAL, 0x52),
                alpha(ACCENT, 0x47), alpha(NEUTRAL, 0x33)));
    }

    private static ColorStateList states(int on, int off, int onLocked, int offLocked) {
        int[][] specs = {
                {-android.R.attr.state_enabled, android.R.attr.state_checked},
                {-android.R.attr.state_enabled},
                {android.R.attr.state_checked},
                new int[0],
        };
        return new ColorStateList(specs, new int[]{onLocked, offLocked, on, off});
    }

    private static int alpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private int primaryText() {
        return isNight() ? TEXT_PRIMARY_DARK : TEXT_PRIMARY_LIGHT;
    }

    private int secondaryText() {
        return isNight() ? TEXT_SECONDARY_DARK : TEXT_SECONDARY_LIGHT;
    }

    private void onToggled(Hook hook, boolean enabled) {
        PatchDb.setFlag(hook.id(), enabled);
        // The hook is already installed or already absent for this process:
        // ArtHooks redirects a method, and nothing puts it back.
        pendingNotice.setVisibility(View.VISIBLE);
    }

    private View restartButton() {
        Button button = new Button(this);
        button.setText("Restart WhatsApp");
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setLetterSpacing(0.01f);
        button.setTextColor(ON_ACCENT);
        // The framework button draws its own grey nine-patch and lifts on
        // press; both have to go before a flat pill looks like anything.
        button.setStateListAnimator(null);
        button.setBackground(pill());
        button.setPadding(dp(24), dp(14), dp(24), dp(14));
        button.setMinimumHeight(dp(52));
        button.setOnClickListener(view -> restart());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(24);
        button.setLayoutParams(params);
        return button;
    }

    /** A solid green pill with a press ripple, drawn rather than themed. */
    private Drawable pill() {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(dp(26));
        shape.setColor(ACCENT);
        return new RippleDrawable(ColorStateList.valueOf(alpha(ON_ACCENT, 0x33)), shape, null);
    }

    private View footer() {
        TextView footer = new TextView(this);
        footer.setText("Switches take effect the next time WhatsApp starts.");
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        footer.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        footer.setTextColor(secondaryText());
        footer.setPadding(0, dp(12), 0, 0);
        return footer;
    }

    /**
     * Keeps the content clear of the system bars and the action bar.
     *
     * The host app targets an SDK that forces edge-to-edge and this activity
     * inherits that, so the window runs the full height of the display and the
     * action bar is laid over the content rather than above it; without this
     * the first row renders underneath both.
     *
     * The action bar needs no term of its own. Measured on device, the top
     * inset handed to the content is already the status bar plus the action
     * bar -- 299px where the action bar ends at y=299 -- because the decor
     * folds the bar it overlays into what it dispatches downwards. Adding
     * actionBarSize on top of that counted the bar twice and left an empty
     * band the height of one action bar above the first row.
     */
    private void insetBelowSystemBars(View content) {
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    /**
     * Kills the process, having first asked the system to launch WhatsApp
     * again shortly afterwards.
     *
     * The alarm is best effort: if scheduling it fails the process still dies,
     * which is the part that actually applies the change, and the user reopens
     * WhatsApp themselves.
     */
    private void restart() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                PendingIntent pending = PendingIntent.getActivity(this, 0, launch,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
                AlarmManager alarms = getSystemService(AlarmManager.class);
                if (alarms != null) {
                    alarms.set(AlarmManager.RTC,
                            System.currentTimeMillis() + RESTART_DELAY_MS, pending);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "PatchSettingsActivity: could not schedule the relaunch", t);
        }
        finishAffinity();
        Process.killProcess(Process.myPid());
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
