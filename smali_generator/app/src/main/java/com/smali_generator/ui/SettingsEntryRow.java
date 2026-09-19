package com.smali_generator.ui;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.TextView;

/**
 * The patcher's own row inside WhatsApp's settings list.
 *
 * WhatsApp strips resource names, so the list cannot be found by id and the
 * row cannot be inflated from a layout. Both are done structurally: the list
 * is the view group holding the most clickable, labelled children, and the row
 * is built in code, borrowing its typography and metrics from a row already in
 * that list so it matches whatever theme is in force.
 *
 * Every failure is silent by design -- the settings screen renders exactly as
 * WhatsApp intended, one row short.
 */
public final class SettingsEntryRow {
    private static final String TAG = "PATCH";

    /** Identifies our row so a second resume does not add another one. */
    private static final String ROW_TAG = "com.smali_generator.settings_row";

    private static final String ROW_TITLE = "Patcher";
    private static final String ROW_SUBTITLE = "Extra features added by the patch";

    /**
     * How many rows a view group needs before it is taken for the settings
     * list. WhatsApp's shows eight above the fold; the runner-up container on
     * that screen scores two.
     */
    private static final int MIN_ROWS = 4;

    /** Where a native row's title starts, when the real one cannot be measured. */
    private static final int FALLBACK_TITLE_INSET_DP = 72;

    private static final int FALLBACK_VERTICAL_PADDING_DP = 16;

    private static boolean loggedNoContainer;

    private SettingsEntryRow() {
    }

    /**
     * Adds the row to the settings list of an activity that is showing one.
     *
     * Idempotent, and safe to call on every resume: that is what repairs the
     * row when an earlier attempt ran before the list had been populated.
     */
    public static void injectInto(Activity activity) {
        try {
            View root = activity.getWindow().getDecorView();
            if (root.findViewWithTag(ROW_TAG) != null) {
                return;
            }
            ViewGroup container = findRowList(root);
            if (container == null) {
                // Once only: this runs on every resume of the settings screen.
                if (!loggedNoContainer) {
                    loggedNoContainer = true;
                    Log.e(TAG, "SettingsEntryRow: no settings list found (logged once)");
                }
                return;
            }
            container.addView(buildRow(activity, container), 0,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
            Log.i(TAG, "SettingsEntryRow: added row to " + container.getClass().getName());
        } catch (Throwable t) {
            Log.e(TAG, "SettingsEntryRow: inject failed", t);
        }
    }

    /**
     * The view group holding the settings rows: the one with the most
     * clickable, labelled children.
     *
     * A clear winner is required. Ties mean the screen does not look like a
     * settings list at all, and guessing between two candidates is how a row
     * ends up somewhere absurd.
     */
    private static ViewGroup findRowList(View root) {
        ViewGroup best = null;
        int bestScore = 0;
        int secondScore = 0;
        for (ViewGroup group : viewGroupsIn(root)) {
            int score = rowsIn(group);
            if (score > bestScore) {
                secondScore = bestScore;
                bestScore = score;
                best = group;
            } else if (score > secondScore) {
                secondScore = score;
            }
        }
        if (bestScore < MIN_ROWS || bestScore <= secondScore) {
            return null;
        }
        return best;
    }

    private static java.util.List<ViewGroup> viewGroupsIn(View root) {
        java.util.List<ViewGroup> found = new java.util.ArrayList<>();
        java.util.ArrayDeque<View> pending = new java.util.ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            View view = pending.removeFirst();
            if (!(view instanceof ViewGroup)) {
                continue;
            }
            ViewGroup group = (ViewGroup) view;
            // A recycling container owns its children: a view added by hand is
            // detached again the moment the adapter rebinds.
            if (!recycles(group)) {
                found.add(group);
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                pending.add(group.getChildAt(i));
            }
        }
        return found;
    }

    private static boolean recycles(ViewGroup group) {
        if (group instanceof AdapterView) {
            return true;
        }
        for (Class<?> cls = group.getClass(); cls != null; cls = cls.getSuperclass()) {
            if (cls.getName().endsWith("RecyclerView")) {
                return true;
            }
        }
        return false;
    }

    private static int rowsIn(ViewGroup group) {
        int rows = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (isRow(group.getChildAt(i))) {
                rows++;
            }
        }
        return rows;
    }

    /** A settings row: tappable, a container, and carrying a label. */
    private static boolean isRow(View view) {
        return view instanceof ViewGroup
                && view.isClickable()
                && InjectedRow.firstTextView(view) != null;
    }

    private static View templateRow(ViewGroup container) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (isRow(child)) {
                return child;
            }
        }
        return null;
    }

    private static View buildRow(Activity activity, ViewGroup container) {
        View template = templateRow(container);
        TextView templateTitle = template == null ? null : InjectedRow.firstTextView(template);
        TextView templateSubtitle =
                template == null ? null : InjectedRow.secondTextView(template, templateTitle);

        View row = InjectedRow.build(activity, ROW_TITLE, ROW_SUBTITLE,
                templateTitle, templateSubtitle,
                titleInset(template, templateTitle, activity),
                InjectedRow.dp(activity, FALLBACK_VERTICAL_PADDING_DP),
                ROW_TAG, () -> open(activity));
        // Measured off a real row so ours is not the short one in the list.
        if (template != null && template.getHeight() > 0) {
            row.setMinimumHeight(template.getHeight());
        }
        return row;
    }

    private static void open(Activity activity) {
        try {
            activity.startActivity(new Intent(activity, PatchSettingsActivity.class));
        } catch (Throwable t) {
            Log.e(TAG, "SettingsEntryRow: could not open the settings screen", t);
        }
    }

    /**
     * How far a native row's text sits from the row's leading edge, measured
     * rather than assumed: it is the icon column, and WhatsApp has changed it.
     */
    private static int titleInset(View template, TextView templateTitle, Activity activity) {
        int fallback = InjectedRow.dp(activity, FALLBACK_TITLE_INSET_DP);
        if (template == null || templateTitle == null || template.getWidth() <= 0) {
            return fallback;
        }
        int[] rowAt = new int[2];
        int[] titleAt = new int[2];
        template.getLocationOnScreen(rowAt);
        templateTitle.getLocationOnScreen(titleAt);
        int inset = template.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                ? (rowAt[0] + template.getWidth()) - (titleAt[0] + templateTitle.getWidth())
                : titleAt[0] - rowAt[0];
        // A template that has not been laid out, or one whose first label is
        // not the title, can produce anything; only a plausible inset is used.
        if (inset <= 0 || inset > template.getWidth() / 2) {
            return fallback;
        }
        return inset;
    }
}
