package com.smali_generator.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Bundle;
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

import com.smali_generator.BootHealth;
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

    private static final int SIDE_PADDING_DP = 20;
    private static final int ROW_PADDING_DP = 14;

    private TextView pendingNotice;

    /** Survives the rebuild in onResume, which is what shows a summary changed
     *  on the picker screen; without it the notice would vanish on the way back. */
    private boolean restartPending;

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
    }

    /**
     * Rebuilt on every resume rather than only on create: a hook's summary line
     * is written by its own configuration screen, so coming back from one has
     * to re-read it.
     */
    @Override
    protected void onResume() {
        super.onResume();
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

        View safeMode = safeModeNotice();
        if (safeMode != null) {
            content.addView(safeMode);
        }

        pendingNotice = new TextView(this);
        pendingNotice.setText("Restart WhatsApp to apply the change.");
        pendingNotice.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        pendingNotice.setTextColor(Palette.ACCENT);
        pendingNotice.setPadding(0, 0, 0, dp(12));
        pendingNotice.setVisibility(restartPending ? View.VISIBLE : View.GONE);
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

    /**
     * Says that the patch has stood aside, and offers the way back.
     *
     * Only the hooks the patched app cannot run without are loaded in this
     * state -- which is why this screen is still reachable to say so. The
     * switches below it read the flags they always did, so they will not match
     * what is actually running until the patch is let back in; the notice says
     * that rather than the screen silently lying.
     */
    private View safeModeNotice() {
        if (!BootHealth.inSafeMode()) {
            return null;
        }
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, 0, 0, dp(12));

        TextView text = new TextView(this);
        text.setText("Safe mode. WhatsApp failed to start twice, so only the hooks it needs to run "
                + "at all were loaded — the switches below are not in force. Nothing has been lost.");
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        text.setTextColor(Palette.ACCENT);
        block.addView(text);

        Button leave = new Button(this);
        leave.setText("Turn the other hooks back on");
        leave.setAllCaps(false);
        leave.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        leave.setTypeface(Typeface.DEFAULT_BOLD);
        leave.setTextColor(Palette.ACCENT);
        leave.setStateListAnimator(null);
        leave.setBackground(Palette.rowRipple(this));
        leave.setMinWidth(0);
        leave.setMinimumWidth(0);
        leave.setMinHeight(0);
        leave.setMinimumHeight(dp(40));
        leave.setPadding(0, dp(6), dp(12), dp(6));
        leave.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        leave.setOnClickListener(view -> {
            BootHealth.leaveSafeMode();
            restartPending = true;
            setContentView(buildContent());
        });
        block.addView(leave);
        return block;
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
        title.setTextColor(Palette.ACCENT);
        header.addView(title);

        if (!category.caption().isEmpty()) {
            TextView caption = new TextView(this);
            caption.setText(category.caption());
            caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            caption.setTextColor(Palette.secondaryText(this));
            header.addView(caption);
        }
        return header;
    }

    private View hookRow(Hook hook) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.addView(switchRow(hook));
        View config = configRow(hook);
        if (config != null) {
            block.addView(config);
        }
        return block;
    }

    /**
     * The line a hook uses to say how it is configured, and the way in.
     *
     * Shown for any hook that declares both a summary and a screen, so this
     * stays as generic as the rest of the list: nothing here knows what read
     * receipts are.
     */
    private View configRow(Hook hook) {
        String summary = hook.configSummary();
        Class<?> screen = hook.configScreen();
        if (summary == null || screen == null) {
            return null;
        }
        TextView row = new TextView(this);
        row.setText(summary + "  \u203A");
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        row.setTypeface(Typeface.DEFAULT_BOLD);
        row.setTextColor(Palette.ACCENT);
        row.setPadding(0, 0, 0, dp(ROW_PADDING_DP));
        row.setBackground(Palette.rowRipple(this));
        row.setOnClickListener(view -> {
            try {
                startActivity(new Intent(this, screen));
            } catch (Throwable t) {
                Log.e(TAG, "PatchSettingsActivity: could not open " + screen.getName(), t);
            }
        });
        return row;
    }

    private View switchRow(Hook hook) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(ROW_PADDING_DP), 0, dp(ROW_PADDING_DP));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(hook.title());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        title.setTextColor(Palette.primaryText(this));
        text.addView(title);

        // No "Required." here any more: the Mandatory section says it once,
        // for all of them.
        if (!hook.description().isEmpty()) {
            TextView subtitle = new TextView(this);
            subtitle.setText(hook.description());
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            subtitle.setTextColor(Palette.secondaryText(this));
            text.addView(subtitle);
        }
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(hook.isEnabled());
        toggle.setEnabled(hook.toggleable());
        Palette.paintSwitch(toggle);
        if (hook.toggleable()) {
            toggle.setOnCheckedChangeListener((button, checked) -> onToggled(hook, checked));
        }
        row.addView(toggle);
        return row;
    }

    private void onToggled(Hook hook, boolean enabled) {
        PatchDb.setFlag(hook.id(), enabled);
        // The hook is already installed or already absent for this process:
        // ArtHooks redirects a method, and nothing puts it back.
        restartPending = true;
        pendingNotice.setVisibility(View.VISIBLE);
    }

    private View restartButton() {
        Button button = Restart.button(this, "Restart WhatsApp");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(24);
        button.setLayoutParams(params);
        return button;
    }

    private View footer() {
        TextView footer = new TextView(this);
        footer.setText("Switches take effect the next time WhatsApp starts.");
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        footer.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        footer.setTextColor(Palette.secondaryText(this));
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

    private int dp(int dp) {
        return Palette.dp(this, dp);
    }
}
