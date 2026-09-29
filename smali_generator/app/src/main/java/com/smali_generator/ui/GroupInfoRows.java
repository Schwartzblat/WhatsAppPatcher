package com.smali_generator.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.smali_generator.ActivityResume;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * The patcher's own rows inside WhatsApp's group info screen.
 *
 * They go in the screen's own settings group -- the card of Manage storage /
 * Notifications / Media visibility -- because that is a list of things you do
 * to this group, which is what these screens are.
 *
 * This used to anchor on the Members/Media/Settings tab strip instead, and
 * that was too narrow a target. A Galaxy S25+ on the same build and the same
 * WhatsApp version renders a group info screen with **no tab strip at all**:
 * the details card runs straight into the participants card, and the two
 * TabLayouts are inflated but never given tabs. The diagnostic said so exactly
 * -- "2 horizontal scroller(s), 2 holding one child, 2 of those a LinearLayout,
 * 0 carrying 2+ labelled tabs" -- which is the shape of an A/B variant, not of
 * a timing problem. A settings row is something both variants have.
 *
 * The container is found by shape, never by resource name: a vertical
 * LinearLayout, not inside an AdapterView's recycling, whose clickable
 * children all carry a label. {@link SettingsEntryRow}'s "most clickable rows"
 * rule is the same idea; the difference here is that a group info screen has
 * several such groups in a row, so the topmost wins rather than a unique one
 * being demanded.
 *
 * A feature adds itself with {@link #register}; one scan of the view tree
 * serves all of them, and a row appears only while its own hook is loaded. The
 * screen's identity and the group's jid live here rather than in each hook,
 * for the same reason: two features asking the same question of the same
 * screen should not be two places to keep the answer.
 *
 * The watcher never detaches. The settings card exists only while that tab is
 * the one showing, and switching tabs is not a resume -- so a listener that
 * stopped after the first success would put a row on the screen once and
 * never again.
 *
 * Every failure is silent by design: the screen renders exactly as WhatsApp
 * intended, one row short.
 */
public final class GroupInfoRows {
    private static final String TAG = "PATCH";

    /** Resolved by GroupInfoFinder; never written by hand. */
    private static final String GROUP_INFO_ACTIVITY = "{{GROUP_INFO_ACTIVITY_CLASS_NAME}}";

    /** The key WhatsApp has used for the group's jid; the shape check is the fallback. */
    private static final String GID_EXTRA = "gid";

    /** A group jid: plain, or the "created by"-suffixed form older groups use. */
    private static final Pattern GROUP_JID = Pattern.compile("^\\d+(-\\d+)?@g\\.us$");

    /**
     * How many rows a container needs before it is taken for a settings group.
     *
     * Three, because two is what the details card's own pair of action buttons
     * carries, and picking that would put a row up beside the group's photo.
     */
    private static final int MIN_ROWS = 3;

    private static final int VERTICAL_PADDING_DP = 16;

    /**
     * How long to wait before saying out loud that no settings card appeared.
     *
     * A diagnostic, not a deadline -- the watcher keeps going either way. The
     * previous version of this class did treat a budget as a deadline, and
     * counted layout passes rather than time: a screen busy building itself
     * burns forty of them inside the first second, which is precisely the
     * screen that has not finished building.
     */
    private static final long REPORT_AFTER_MS = 15_000L;

    /**
     * The rows to add, in the order their hooks loaded.
     *
     * Copy-on-write because it is written from hook loading and read from
     * every layout pass of a group info screen. Registration happens once per
     * hook at startup, so the copying costs nothing.
     */
    private static final List<Row> rows = new CopyOnWriteArrayList<>();

    /** Whether the resume listener is in place; the first registration installs it. */
    private static boolean listening;

    /**
     * One entry per activity already being watched, so a second resume does
     * not attach a second listener.
     *
     * A WeakHashMap only weakly references the *key*; the *value* is held
     * strongly by the map's own entry. A value that references its key back --
     * directly, or through anything the key reaches, such as its own decor
     * view -- keeps the whole entry reachable from here (a static field, a GC
     * root) regardless of how the map is declared, and the activity never
     * becomes collectible through this map at all. That is why
     * {@link SettingsCardWatcher} holds the activity through a
     * {@link WeakReference} of its own rather than a plain field: it is the
     * listener that must not pin the key, not the map. Activity resumes and
     * layout passes both run on the main thread, so this needs no
     * synchronization.
     */
    private static final Map<Activity, ViewTreeObserver.OnGlobalLayoutListener> pending =
            new WeakHashMap<>();

    private GroupInfoRows() {
    }

    /** What a row does when it is tapped, given the group it was added for. */
    public interface Action {
        void open(Activity activity, String gid);
    }

    /** How a row draws its glyph; {@code Icons} needs the screen's own context. */
    public interface Glyph {
        Drawable of(Activity activity);
    }

    /** One feature's row on the group info screen. */
    public static final class Row {
        /** Identifies this row so a second pass does not add another one. */
        final String tag;
        final String title;
        final String subtitle;
        final Glyph glyph;
        final Action action;

        public Row(String tag, String title, String subtitle, Glyph glyph, Action action) {
            this.tag = tag;
            this.title = title;
            this.subtitle = subtitle;
            this.glyph = glyph;
            this.action = action;
        }
    }

    /**
     * Adds a row to every group info screen from now on.
     *
     * Returns whether the screen can be reached at all -- a hook that gets
     * false has not been installed, and should say so under its own name
     * rather than leave a silent absence.
     */
    public static synchronized boolean register(Row row) {
        for (Row already : rows) {
            if (already.tag.equals(row.tag)) {
                return listening;
            }
        }
        rows.add(row);
        if (!listening) {
            listening = ActivityResume.addListener(GroupInfoRows::onResumed);
            if (listening) {
                Log.i(TAG, "GroupInfoRows: listening for resumes, group info screen is "
                        + GROUP_INFO_ACTIVITY);
            }
        }
        return listening;
    }

    private static void onResumed(Activity activity) {
        if (!GROUP_INFO_ACTIVITY.equals(activity.getClass().getName())) {
            return;
        }
        String gid = gidOf(activity);
        if (gid == null) {
            Log.e(TAG, "GroupInfoRows: no group jid on the group info intent, no rows added");
            return;
        }
        injectWhenReady(activity, gid);
    }

    /**
     * The group jid this screen is showing, or null.
     *
     * Prefers the key WhatsApp uses and falls back to shape, so a renamed key
     * degrades instead of breaking. Two differing group-shaped extras mean the
     * screen is not the one this was written against, and guessing between
     * them would key the whole screen on the wrong chat.
     */
    static String gidOf(Activity activity) {
        Intent intent = activity.getIntent();
        if (intent == null) {
            return null;
        }
        String named = intent.getStringExtra(GID_EXTRA);
        if (named != null && GROUP_JID.matcher(named).matches()) {
            return named;
        }
        Bundle extras = intent.getExtras();
        if (extras == null) {
            return null;
        }
        String found = null;
        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            if (!(value instanceof String) || !GROUP_JID.matcher((String) value).matches()) {
                continue;
            }
            if (found != null && !found.equals(value)) {
                Log.e(TAG, "GroupInfoRows: two different group jids on the intent, declining");
                return null;
            }
            found = (String) value;
        }
        return found;
    }

    /**
     * Starts watching a group info screen for its settings card.
     *
     * Safe to call on every resume: {@link #pending} is what stops a second
     * resume from attaching a second listener onto the same activity.
     */
    static void injectWhenReady(Activity activity, String gid) {
        if (pending.containsKey(activity)) {
            return;
        }
        SettingsCardWatcher listener = new SettingsCardWatcher(activity, gid);
        pending.put(activity, listener);
        View decor = activity.getWindow().getDecorView();
        decor.getViewTreeObserver().addOnGlobalLayoutListener(listener);
        // Posted rather than checked on a layout pass: layout passes stop once
        // a screen settles, and a screen that has settled with no row on it is
        // the exact failure worth reporting. The listener holds the activity
        // weakly, so this message cannot pin it either.
        decor.postDelayed(listener::report, REPORT_AFTER_MS);
        // Tabs are rendered after the first resume on a cold open, so this
        // first try usually fails; the listener is what does the work.
        listener.onGlobalLayout();
    }

    /**
     * Re-adds any missing row whenever the settings card is on screen.
     *
     * Holds the activity through a {@link WeakReference} rather than a plain
     * field -- see the comment on {@link #pending} for why a strong reference
     * here would defeat that map's weak keys entirely. A decor view is not a
     * safe substitute: its context *is* the activity, so holding the view
     * would pin the activity just as hard.
     *
     * When the activity has been collected there is nothing to detach and no
     * way to: once a window is gone no further layout passes fire, so this
     * listener simply stops doing anything, and the {@link #pending} entry
     * goes with its key the next time the map is touched.
     */
    private static final class SettingsCardWatcher implements ViewTreeObserver.OnGlobalLayoutListener {
        private final WeakReference<Activity> activityRef;
        private final String gid;

        private int passes;

        SettingsCardWatcher(Activity activity, String gid) {
            this.activityRef = new WeakReference<>(activity);
            this.gid = gid;
        }

        @Override
        public void onGlobalLayout() {
            Activity activity = activityRef.get();
            if (activity == null) {
                return;
            }
            passes++;
            injectInto(activity, gid);
        }

        /**
         * Said once, and only about the case worth knowing: a hook that logs
         * nothing is indistinguishable from one that never ran, and "no row
         * added" alone cannot say whether the screen had no settings card, or
         * one whose rows were not measured yet -- different bugs, one symptom.
         */
        void report() {
            Activity activity = activityRef.get();
            if (activity == null || injectInto(activity, gid)) {
                return;
            }
            Log.e(TAG, "GroupInfoRows: rows missing after " + REPORT_AFTER_MS + "ms and "
                    + passes + " layout pass(es) -- " + why(activity.getWindow().getDecorView()));
        }
    }

    /** Which of this class's two failures happened, for the log. */
    private static String why(View root) {
        Scan found = scan(root);
        if (found.card == null) {
            return "no settings card: " + found;
        }
        View template = lastVisibleRow(found.card);
        int[] at = new int[2];
        found.card.getLocationOnScreen(at);
        return "settings card at " + at[0] + "," + at[1] + " sized "
                + found.card.getWidth() + "x" + found.card.getHeight()
                + ", last row " + (template == null ? "absent" : template.getWidth()
                        + "px wide, label inset " + insetOf(template) + "px")
                + "; " + found;
    }

    /**
     * A single attempt to put every registered row on whatever settings card
     * is showing.
     *
     * Idempotent: a row already present, from this call or an earlier one, is
     * left alone. Returns whether they are all present when this call returns,
     * not whether this call is the one that added them.
     */
    static boolean injectInto(Activity activity, String gid) {
        try {
            View root = activity.getWindow().getDecorView();
            ViewGroup card = null;
            for (Row row : rows) {
                if (root.findViewWithTag(row.tag) != null) {
                    continue;
                }
                if (card == null) {
                    card = scan(root).card;
                    if (card == null) {
                        return false;
                    }
                }
                // Styled off the row it will sit under, so it inherits whatever
                // theme, font scale and dark mode are in force. Copying a real
                // neighbour is the only way to get that: WhatsApp's own row
                // layout is not something this can inflate, and a layout of
                // ours would carry our theme rather than the one this screen is
                // drawn in.
                View template = lastVisibleRow(card);
                int inset = insetOf(template);
                if (inset <= 0) {
                    // The card is on screen but has not been measured yet,
                    // which is the usual state on the layout pass that first
                    // reveals it. Waiting costs a frame; guessing an inset
                    // costs a row that sits a centimetre left of every row
                    // above it, which is what a default of 24dp looked like.
                    return false;
                }
                card.addView(buildRow(activity, row, template, inset, gid));
                Log.i(TAG, "GroupInfoRows: added the " + row.title
                        + " row to the settings card for " + gid);
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "GroupInfoRows: inject failed", t);
            return false;
        }
    }

    /**
     * What one walk of the view tree found, and how far each candidate got.
     *
     * The counts are the whole point of keeping this as an object: on a device
     * where a row never appears, the question is which of these stages the
     * screen falls out of, and a boolean cannot answer it. That is what named
     * the S25+ variant in one round trip instead of several.
     */
    private static final class Scan {
        /** The topmost settings card on the screen, or null if there was none. */
        ViewGroup card;

        int vertical;
        int enoughRows;
        int labelled;
        int shown;
        int measured;

        @Override
        public String toString() {
            return vertical + " vertical LinearLayout(s) outside an AdapterView, "
                    + enoughRows + " with " + MIN_ROWS + "+ clickable children, "
                    + labelled + " whose children all carry a label, "
                    + shown + " of those shown, "
                    + measured + " of those measured";
        }
    }

    /**
     * The topmost settings card: a vertical LinearLayout, outside any
     * AdapterView's recycling, whose clickable children every one carry a
     * label of their own.
     *
     * Topmost rather than unique, because a group info screen legitimately
     * carries several of these -- the actions card, then the security card --
     * and "the first group of settings" is a position a reader can predict.
     * The AdapterView exclusion is the one hard rule: a child added by hand to
     * one is detached the moment its adapter rebinds.
     */
    private static Scan scan(View root) {
        Scan result = new Scan();
        ViewGroup best = null;
        int bestTop = Integer.MAX_VALUE;
        int[] at = new int[2];
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.removeFirst();
            if (!(view instanceof ViewGroup)) {
                continue;
            }
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                queue.add(group.getChildAt(i));
            }
            if (group instanceof AdapterView || !(group instanceof LinearLayout)
                    || ((LinearLayout) group).getOrientation() != LinearLayout.VERTICAL) {
                continue;
            }
            result.vertical++;
            int rowCount = 0;
            int labelled = 0;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                // Width, not isClickable alone: a GONE row is still clickable
                // and still zero-sized, and a card can end in several of them
                // -- a group that has no disappearing-messages row still
                // carries the view for it.
                if (!child.isClickable() || child.getWidth() <= 0) {
                    continue;
                }
                rowCount++;
                if (InjectedRow.firstTextView(child) != null) {
                    labelled++;
                }
            }
            if (rowCount < MIN_ROWS) {
                continue;
            }
            result.enoughRows++;
            // All of them, not most: a strip of clickable thumbnails is a row
            // of controls too, and it is not a settings group.
            if (labelled < rowCount) {
                continue;
            }
            result.labelled++;
            if (!group.isShown()) {
                continue;
            }
            result.shown++;
            // getLocationOnScreen answers (0, 0) for a view that has never
            // been laid out, which makes an unmeasured candidate the topmost
            // one on the screen and hands it the row for ever. The tab a group
            // info screen is not showing keeps exactly such a view around.
            if (group.getWidth() <= 0 || group.getHeight() <= 0) {
                continue;
            }
            result.measured++;
            group.getLocationOnScreen(at);
            if (at[1] < bestTop) {
                bestTop = at[1];
                best = group;
            }
        }
        result.card = best;
        return result;
    }

    /**
     * The last row of a card that is actually on the screen.
     *
     * The last *child* is not it: a settings card ends in however many rows
     * this particular group does not have, all of them GONE, all of them still
     * clickable and zero-sized. Taking one as the template measured an inset
     * off a view with no width, which is how the row spent three builds not
     * appearing at all.
     *
     * A row of ours already added is skipped: it is a legitimate template in
     * shape, but measuring the second row off the first would compound any
     * error in the first, and ours is built rather than inflated.
     */
    private static View lastVisibleRow(ViewGroup card) {
        for (int i = card.getChildCount() - 1; i >= 0; i--) {
            View child = card.getChildAt(i);
            if (child.getTag() instanceof String && ((String) child.getTag()).startsWith(Tags.PREFIX)) {
                continue;
            }
            if (child.isClickable() && child.getWidth() > 0) {
                return child;
            }
        }
        return null;
    }

    /** The namespace every row this class adds is tagged under. */
    public static final class Tags {
        public static final String PREFIX = "com.smali_generator.group_info_row.";

        private Tags() {
        }
    }

    private static View buildRow(Activity activity, Row row, View template, int inset, String gid) {
        // The patcher's green rather than the card's own icon colour: these
        // rows are not WhatsApp's, and the rest of the patcher's UI says so in
        // the same green.
        return InjectedRow.build(activity, row.title, row.subtitle,
                template, row.glyph.of(activity), inset,
                InjectedRow.dp(activity, VERTICAL_PADDING_DP),
                row.tag, () -> {
                    try {
                        row.action.open(activity, gid);
                    } catch (Throwable t) {
                        Log.e(TAG, "GroupInfoRows: could not open " + row.title, t);
                    }
                });
    }

    /**
     * Where a row's own text starts, measured off it rather than assumed:
     * WhatsApp has changed this margin, and it is the width of the icon column
     * this row now draws into as well, so the number has to come from a real
     * neighbour.
     *
     * Returns -1 when the row cannot answer -- not laid out yet, or no label
     * in it -- and an implausible offset counts as no answer.
     */
    private static int insetOf(View row) {
        if (row == null || row.getWidth() <= 0) {
            return -1;
        }
        TextView text = InjectedRow.firstTextView(row);
        if (text == null || text.getWidth() <= 0) {
            return -1;
        }
        int[] rowAt = new int[2];
        int[] textAt = new int[2];
        row.getLocationOnScreen(rowAt);
        text.getLocationOnScreen(textAt);
        int inset = row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                ? (rowAt[0] + row.getWidth()) - (textAt[0] + text.getWidth())
                : textAt[0] - rowAt[0];
        return inset >= 0 && inset <= row.getWidth() / 2 ? inset : -1;
    }
}
