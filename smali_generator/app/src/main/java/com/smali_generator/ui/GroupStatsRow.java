package com.smali_generator.ui;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The statistics row inside WhatsApp's group info screen.
 *
 * The whole screen is one ListView -- photo, title, buttons, tab strip and
 * participants alike -- so {@link SettingsEntryRow}'s "view group with the
 * most clickable rows" heuristic finds nothing here: it refuses AdapterViews,
 * and rightly, because a child added by hand to one is detached the moment the
 * adapter rebinds.
 *
 * The anchor used instead is the tab bar (Members / Media / Settings), which
 * lives in the ListView's header view and is therefore a real, non-recycled
 * child. It is the only shown HorizontalScrollView on the screen, and its
 * length varies by group -- three tabs on one, two on another -- so nothing
 * here assumes a fixed number of them. The row goes into the tab bar's
 * parent, immediately below it -- a sibling of the whole bar, not a child of
 * it: the bar is a Material TabLayout, and TabLayout overrides addView to
 * accept only TabItem.
 *
 * Every failure is silent by design: the screen renders exactly as WhatsApp
 * intended, one row short.
 */
public final class GroupStatsRow {
    private static final String TAG = "PATCH";

    /** Identifies our row so a second resume does not add another one. */
    private static final String ROW_TAG = "com.smali_generator.group_stats_row";

    private static final String ROW_TITLE = "Statistics";
    private static final String ROW_SUBTITLE = "Who talks most, and when";

    /** A strip needs at least this many tabs before it is taken for one. */
    private static final int MIN_TABS = 2;

    private static final int VERTICAL_PADDING_DP = 16;
    private static final int FALLBACK_START_INSET_DP = 24;

    /**
     * Generous but bounded: the header populates within the first few layout
     * passes on every build tested. The cap exists so a screen that never
     * grows a tab bar stops walking the view tree on every scroll frame --
     * 40 is not itself a meaningful number.
     */
    private static final int MAX_LAYOUT_ATTEMPTS = 40;

    /**
     * One entry per activity currently waiting for its tab bar to appear, so
     * a resume that arrives while an earlier one is still waiting does not
     * attach a second listener.
     *
     * A WeakHashMap only weakly references the *key*; the *value* is held
     * strongly by the map's own entry. A value that references its key back
     * -- directly, or through anything the key reaches, such as its own
     * decor view -- keeps the whole entry reachable from here (a static
     * field, a GC root) regardless of how the map is declared, and the
     * activity never becomes collectible through this map at all. That is
     * why {@link TabBarWaiter} holds the activity through a
     * {@link WeakReference} of its own rather than a plain field: it is the
     * listener that must not pin the key, not the map. Activity resumes and
     * layout passes both run on the main thread, so this needs no
     * synchronization.
     */
    private static final Map<Activity, ViewTreeObserver.OnGlobalLayoutListener> pending = new WeakHashMap<>();

    private GroupStatsRow() {
    }

    /**
     * Adds the row to a group info screen, waiting for its tab bar if the
     * header has not finished laying out yet.
     *
     * A resume that lands on an already-populated screen -- a return to one
     * seen before -- is a single synchronous {@link #injectInto} call with no
     * listener at all. A first visit, where the tab bar is still being filled
     * in by WhatsApp's own async load, attaches a layout listener instead of
     * guessing at a delay: it retries on every layout pass and detaches
     * itself the moment the row is present, or after
     * {@link #MAX_LAYOUT_ATTEMPTS} passes, whichever comes first.
     *
     * Safe to call on every resume: {@link #pending} is what stops a second
     * resume, arriving while an earlier one is still waiting, from attaching
     * a second listener onto the same activity.
     */
    public static void injectWhenReady(Activity activity, String gid) {
        if (injectInto(activity, gid)) {
            return;
        }
        if (pending.containsKey(activity)) {
            return;
        }
        TabBarWaiter listener = new TabBarWaiter(activity, gid);
        pending.put(activity, listener);
        activity.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(listener);
    }

    /**
     * Retries {@link #injectInto} on every layout pass until it succeeds or
     * {@link #MAX_LAYOUT_ATTEMPTS} is reached.
     *
     * Holds the activity through a {@link WeakReference} rather than a plain
     * field -- see the comment on {@link #pending} for why a strong
     * reference here would defeat that map's weak keys entirely. A decor
     * view is not a safe substitute: its context *is* the activity, so
     * holding the view would pin the activity just as hard. The activity is
     * re-derived from the reference on every call instead.
     *
     * When the activity has been collected, there is nothing to detach and
     * no way to: once a window is detached no further layout passes fire, so
     * this listener simply stops doing anything, and the {@link #pending}
     * entry that key with it is purged by the WeakHashMap itself the next
     * time it is touched -- which is the entire point of not pinning it.
     */
    private static final class TabBarWaiter implements ViewTreeObserver.OnGlobalLayoutListener {
        private final WeakReference<Activity> activityRef;
        private final String gid;
        private int attempts;

        TabBarWaiter(Activity activity, String gid) {
            this.activityRef = new WeakReference<>(activity);
            this.gid = gid;
        }

        @Override
        public void onGlobalLayout() {
            Activity activity = activityRef.get();
            if (activity == null) {
                return;
            }
            attempts++;
            boolean added = injectInto(activity, gid);
            if (!added && attempts < MAX_LAYOUT_ATTEMPTS) {
                return;
            }
            ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnGlobalLayoutListener(this);
            }
            pending.remove(activity);
            if (!added) {
                // The one signal that matters here: a hook that logs
                // nothing is indistinguishable from one that never ran,
                // and this is the case that is actually worth knowing
                // about -- the routine "not there on the first layout
                // pass" case below is not.
                Log.e(TAG, "GroupStatsRow: gave up waiting for the tab strip after "
                        + MAX_LAYOUT_ATTEMPTS + " layout passes, no row added");
            }
        }
    }

    /**
     * A single attempt to add the row to a group info screen that is showing
     * its tab bar.
     *
     * Idempotent: a row already present, from this call or an earlier one, is
     * left alone. Returns whether the row is present when this call returns
     * -- not just whether this call is the one that added it -- which is what
     * lets {@link #injectWhenReady} tell success from "not yet" without
     * knowing which attempt did the work.
     */
    public static boolean injectInto(Activity activity, String gid) {
        try {
            View root = activity.getWindow().getDecorView();
            if (root.findViewWithTag(ROW_TAG) != null) {
                return true;
            }
            ViewGroup tabBar = findTabStrip(root);
            if (tabBar == null) {
                return false;
            }
            ViewGroup parent = (ViewGroup) tabBar.getParent();
            int at = parent.indexOfChild(tabBar) + 1;
            parent.addView(buildRow(activity, tabBar, gid), at);
            Log.i(TAG, "GroupStatsRow: added row below the tab strip for " + gid);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "GroupStatsRow: inject failed", t);
            return false;
        }
    }

    /**
     * The tab bar: the sole shown HorizontalScrollView whose only child is a
     * LinearLayout of labelled tabs. Returns the bar itself, not that inner
     * LinearLayout -- see the comment below on why the distinction matters.
     *
     * A clear single winner is required. More than one candidate means the
     * screen is not the one this was written against, and guessing is how a
     * row ends up somewhere absurd.
     */
    private static ViewGroup findTabStrip(View root) {
        List<ViewGroup> found = new ArrayList<>();
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            View view = pending.removeFirst();
            if (!(view instanceof ViewGroup)) {
                continue;
            }
            ViewGroup group = (ViewGroup) view;
            if (view instanceof HorizontalScrollView && group.getChildCount() == 1) {
                View child = group.getChildAt(0);
                // The header carries two structurally identical tab layouts -- one of
                // them GONE and zero-size, presumably a leftover or measurement copy --
                // and uiautomator does not dump GONE views, so a UI dump shows only the
                // real one. isShown() is what tells them apart here; a size check would
                // do the same today but would also reject the real bar on a resume
                // whose post() callback lands before layout, which isShown() cannot.
                //
                // What gets stored is `group`, the outer HorizontalScrollView, not
                // `child`. The matched inner LinearLayout is Material's own
                // SlidingTabIndicator, and its parent -- this `group` -- is a
                // TabLayout, which overrides addView to accept only TabItem. Anything
                // this class adds has to go beside the bar, in its own parent, never
                // into it.
                if (child instanceof LinearLayout && isTabStrip((ViewGroup) child)
                        && child.getParent() instanceof ViewGroup
                        && group.getParent() instanceof ViewGroup
                        && child.isShown()) {
                    found.add(group);
                }
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                pending.add(group.getChildAt(i));
            }
        }
        return found.size() == 1 ? found.get(0) : null;
    }

    /** A strip of tabs: several children, each carrying a label of its own. */
    private static boolean isTabStrip(ViewGroup group) {
        int tabs = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (InjectedRow.firstTextView(group.getChildAt(i)) != null) {
                tabs++;
            }
        }
        return tabs >= MIN_TABS;
    }

    private static View buildRow(Activity activity, ViewGroup tabBar, String gid) {
        TextView tabLabel = InjectedRow.firstTextView(tabBar);
        ViewGroup parent = (ViewGroup) tabBar.getParent();
        // The section headers below the strip are where the screen's own text
        // starts; matching them is what keeps the row from looking inset wrong.
        int inset = startInset(parent, tabBar);
        return InjectedRow.build(activity, ROW_TITLE, ROW_SUBTITLE,
                tabLabel, null, inset,
                InjectedRow.dp(activity, VERTICAL_PADDING_DP),
                ROW_TAG, () -> open(activity, gid));
    }

    /**
     * Where the screen's own body text starts, measured off a sibling rather
     * than assumed: WhatsApp has changed this margin.
     */
    private static int startInset(ViewGroup parent, ViewGroup tabBar) {
        int fallback = InjectedRow.dp(parent.getContext(), FALLBACK_START_INSET_DP);
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child == tabBar || child.getWidth() <= 0) {
                continue;
            }
            TextView text = InjectedRow.firstTextView(child);
            if (text == null || text.getWidth() <= 0) {
                continue;
            }
            int[] rowAt = new int[2];
            int[] textAt = new int[2];
            child.getLocationOnScreen(rowAt);
            text.getLocationOnScreen(textAt);
            int inset = child.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                    ? (rowAt[0] + child.getWidth()) - (textAt[0] + text.getWidth())
                    : textAt[0] - rowAt[0];
            // A child that has not been laid out can produce anything; only a
            // plausible inset is used.
            if (inset > 0 && inset <= child.getWidth() / 2) {
                return inset;
            }
        }
        return fallback;
    }

    private static void open(Activity activity, String gid) {
        try {
            Intent intent = new Intent(activity, GroupStatsActivity.class);
            intent.putExtra(GroupStatsActivity.EXTRA_GID, gid);
            activity.startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "GroupStatsRow: could not open the statistics screen", t);
        }
    }
}
