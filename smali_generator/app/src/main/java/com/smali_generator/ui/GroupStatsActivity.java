package com.smali_generator.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.smali_generator.stats.GroupStatsReader;
import com.smali_generator.utils.Utils;

import java.util.List;

/**
 * What a group's messages add up to.
 *
 * Built programmatically like every screen in this module: the module's
 * resource table is not merged into WhatsApp's, so there is no res/ and
 * nothing may be inflated.
 *
 * No options menu, ever. WhatsApp wraps every activity's Window.Callback --
 * ours included, since they run in its process -- with a Kotlin class whose
 * parameters are checked non-null, and the framework's overflow path calls
 * onMenuOpened(featureId, null). Tapping the three dots takes the whole app
 * down from a stack containing nothing of this patch.
 */
public class GroupStatsActivity extends Activity {

    public static final String EXTRA_GID = "com.smali_generator.gid";

    private static final int SIDE_PADDING_DP = 20;

    private LinearLayout content;
    private String gid;

    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * Sections seen at least once, so a rebuild draws exactly what has
     * actually arrived -- no more (a section not yet reported has nothing to
     * show) and no less (a later snapshot of an already-seen section, with a
     * name resolved or a participant discovered since, still has to reach
     * the screen).
     */
    private final java.util.Set<GroupStatsReader.Section> reached = new java.util.HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Statistics");
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }
        gid = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_GID);
        setContentView(buildContent());
        if (gid == null) {
            content.addView(note("No group was named."));
            return;
        }
        start();
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
        ScrollView scroller = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int side = Palette.dp(this, SIDE_PADDING_DP);
        content.setPadding(side, side, side, side);
        scroller.addView(content);
        // Inset the scroller, not content -- content's own padding is the
        // screen's margin, this is what keeps the first section from drawing
        // under WhatsApp's own action bar, same as ChatPickerActivity's root.
        insetBelowSystemBars(scroller);
        return scroller;
    }

    private void insetBelowSystemBars(View content) {
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    private TextView note(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Palette.secondaryText(this));
        view.setGravity(Gravity.START);
        return view;
    }

    private void start() {
        content.addView(note("Reading messages…"));
        new Thread(() -> GroupStatsReader.run(
                Utils.getApplicationContext(), gid,
                (section, snapshot) -> main.post(() -> draw(section, snapshot))),
                "group-stats").start();
    }

    /**
     * Rebuilds the whole screen from the latest snapshot rather than
     * appending once per section. A snapshot is a complete copy of
     * everything the reader has accumulated so far, not a diff, so a later
     * one -- carrying a name resolved after this ran once, or a
     * reaction-only participant Task 8 discovers after PARTICIPANTS already
     * reported -- is simply redrawn in full. The screen is small and this
     * runs at most a handful of times per open, so the rebuild costs nothing
     * worth guarding against.
     */
    private void draw(GroupStatsReader.Section section, GroupStatsReader.Snapshot snapshot) {
        reached.add(section);
        content.removeAllViews();
        if (reached.contains(GroupStatsReader.Section.SUMMARY)) {
            drawSummary(snapshot);
        }
        if (reached.contains(GroupStatsReader.Section.PARTICIPANTS)) {
            drawParticipants(snapshot);
        }
        if (reached.contains(GroupStatsReader.Section.WHEN)) {
            drawWhen(snapshot);
        }
    }

    private void drawSummary(GroupStatsReader.Snapshot snapshot) {
        content.addView(heading(snapshot.subject == null ? "This group" : snapshot.subject));
        if (snapshot.failed.contains(GroupStatsReader.Section.SUMMARY.name())) {
            content.addView(note("Statistics unavailable — the message store could not be read."));
            return;
        }
        content.addView(note("Only history kept on this phone is counted."));
    }

    private void drawParticipants(GroupStatsReader.Snapshot snapshot) {
        content.addView(heading("Who talks most"));
        if (snapshot.failed.contains(GroupStatsReader.Section.PARTICIPANTS.name())) {
            content.addView(note("Unavailable."));
            return;
        }
        content.addView(note(snapshot.totalMessages + " messages, "
                + when(snapshot.firstTimestamp) + " – " + when(snapshot.lastTimestamp)));
        List<GroupStatsReader.Snapshot.Participant> all = snapshot.participants;
        long top = all.isEmpty() ? 0 : all.get(0).messages;
        for (GroupStatsReader.Snapshot.Participant who : all) {
            content.addView(bar(who.name, who.messages,
                    snapshot.totalMessages == 0 ? 0 : who.messages * 100f / snapshot.totalMessages,
                    top == 0 ? 0f : (float) who.messages / top));
        }
    }

    private String when(long millis) {
        return millis <= 0 ? "?" : DateFormat.getDateFormat(this).format(new java.util.Date(millis));
    }

    private static final String[] WEEKDAYS = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};

    private void drawWhen(GroupStatsReader.Snapshot snapshot) {
        content.addView(heading("When"));
        if (snapshot.failed.contains(GroupStatsReader.Section.WHEN.name())) {
            content.addView(note("Unavailable."));
            return;
        }
        content.addView(note("By hour of day"));
        long peak = max(snapshot.byHour);
        for (int hour = 0; hour < 24; hour++) {
            if (snapshot.byHour[hour] == 0) {
                continue;
            }
            content.addView(bar(String.format(java.util.Locale.getDefault(), "%02d:00", hour),
                    snapshot.byHour[hour], 0f,
                    peak == 0 ? 0f : (float) snapshot.byHour[hour] / peak));
        }
        content.addView(note("By day of week"));
        long busiest = max(snapshot.byWeekday);
        for (int day = 0; day < 7; day++) {
            content.addView(bar(WEEKDAYS[day], snapshot.byWeekday[day], 0f,
                    busiest == 0 ? 0f : (float) snapshot.byWeekday[day] / busiest));
        }
    }

    private static long max(long[] values) {
        long top = 0;
        for (long value : values) {
            if (value > top) {
                top = value;
            }
        }
        return top;
    }

    private TextView heading(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Palette.primaryText(this));
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setPadding(0, Palette.dp(this, 20), 0, Palette.dp(this, 6));
        return view;
    }

    /** One participant: name, count, share, and a bar scaled to the loudest. */
    private View bar(String name, long count, float percent, float fraction) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, Palette.dp(this, 6), 0, Palette.dp(this, 6));

        TextView label = new TextView(this);
        label.setText(percent > 0f
                ? String.format(java.util.Locale.getDefault(), "%s — %d (%.1f%%)", name, count, percent)
                : String.format(java.util.Locale.getDefault(), "%s — %d", name, count));
        label.setTextColor(Palette.primaryText(this));
        row.addView(label);

        View track = new View(this);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(Palette.isNight(this) ? Color.parseColor("#4B8F6B")
                : Color.parseColor("#25D366"));
        fill.setCornerRadius(Palette.dp(this, 3));
        track.setBackground(fill);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, Palette.dp(this, 6));
        params.weight = Math.max(fraction, 0.02f);
        track.setLayoutParams(params);

        LinearLayout trackRow = new LinearLayout(this);
        trackRow.setOrientation(LinearLayout.HORIZONTAL);
        trackRow.addView(track);
        View spacer = new View(this);
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(
                0, Palette.dp(this, 6));
        spacerParams.weight = Math.max(1f - fraction, 0f);
        spacer.setLayoutParams(spacerParams);
        trackRow.addView(spacer);
        row.addView(trackRow);
        return row;
    }
}
