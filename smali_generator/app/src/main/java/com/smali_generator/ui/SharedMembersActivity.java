package com.smali_generator.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.smali_generator.db.Avatars;
import com.smali_generator.db.LidJids;
import com.smali_generator.db.ParticipantNames;
import com.smali_generator.stats.GroupCrossing;
import com.smali_generator.stats.GroupMembers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Who your groups have in common.
 *
 * The screen starts as one group's member list and narrows: every group added
 * removes everybody who is not in it too, so what is left is the people all of
 * them hold. Opened from a group's info screen it starts on that group; opened
 * from the patcher's settings it starts on nothing and asks.
 *
 * The crossing itself is {@link GroupCrossing} and the reading is
 * {@link GroupMembers}; this class is the picking and the drawing. Every read
 * happens on a background thread and lands back through {@link #main}, and a
 * result whose {@link #generation} has been superseded is dropped -- a user
 * ticking four groups in a row starts four crossings, and the one that answers
 * last is not necessarily the one that was asked last.
 *
 * A {@link ListView} rather than rows in a ScrollView: before anything is
 * crossed the list is a whole group, which on the test account runs to 940
 * people. Recycling is the point.
 *
 * Built programmatically like every screen in this module, and with no options
 * menu, ever: WhatsApp wraps every activity's Window.Callback -- ours
 * included, since they run in its process -- with a Kotlin class whose
 * parameters are checked non-null, and the framework's overflow path calls
 * onMenuOpened(featureId, null).
 */
public class SharedMembersActivity extends Activity {
    private static final String TAG = "PATCH";

    public static final String EXTRA_GID = "com.smali_generator.gid";

    /**
     * WhatsApp's own profile screen, and the key it takes its subject under.
     *
     * Both resolved by ContactInfoFinder out of the intent WhatsApp builds
     * for itself, never written down: the class is unobfuscated but has a
     * package that moves -- group info already went from
     * com.whatsapp.groupinfo to com.whatsapp.chatinfo.group -- and a moved
     * activity is an ActivityNotFoundException at the moment a row is
     * tapped. The screen's other two extras are left off deliberately:
     * should_show_chat_action defaults to true, which is what puts the
     * Message button on it, and circular_transition to false.
     */
    private static final String CONTACT_INFO_ACTIVITY = "{{CONTACT_INFO_ACTIVITY_CLASS_NAME}}";
    private static final String JID_EXTRA = "{{CONTACT_INFO_JID_EXTRA}}";

    private static final int SIDE_PADDING_DP = 20;
    private static final int ROW_AVATAR_DP = 40;

    /** The gids being crossed, the one the screen opened on first. */
    private final List<String> selected = new ArrayList<>();

    /** Every group that could be picked, most members first. */
    private final List<GroupMembers.Group> allGroups = new ArrayList<>();

    /** The crossing's answer, named and sorted. */
    private final List<Person> people = new ArrayList<>();

    /** Those of {@link #people} that survive the search field. */
    private final List<Person> visible = new ArrayList<>();

    private final Handler main = new Handler(Looper.getMainLooper());

    /** Owned by the screen so its cache dies with it; see {@link Avatars}. */
    private Avatars avatars;

    private LinearLayout chips;
    private TextView summary;
    private EditText searchBox;
    private ListView list;
    private TextView emptyNotice;
    private PeopleAdapter adapter;

    /** Lower-cased once here rather than per row on every keystroke. */
    private String query = "";

    /** How many people the first group has, which is what the rest filter. */
    private int anchorSize;

    private boolean failed;
    private boolean reading;

    /** Bumped per read, so a slower earlier answer cannot overwrite a newer one. */
    private int generation;

    /** Whether the picker has been offered to a screen that opened with nothing. */
    private boolean offeredPicker;

    /** One row of the result. */
    private static final class Person {
        /** As the first group writes them: what the name and photo lookups want. */
        final String jid;
        final String label;

        /** The name alone, lower-cased, or empty for somebody nothing could name. */
        final String sortKey;

        Person(String jid, String label, String sortKey) {
            this.jid = jid;
            this.label = label;
            this.sortKey = sortKey;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Shared members");
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }
        avatars = new Avatars(this);
        // A group first opened since this process started carries a LID the
        // cached map has never seen, and its members would then cross against
        // nothing.
        LidJids.invalidate();
        String gid = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_GID);
        if (gid != null && !gid.isEmpty()) {
            selected.add(gid);
        }
        setContentView(buildContent());
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = Palette.dp(this, SIDE_PADDING_DP);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(side, Palette.dp(this, 12), side, Palette.dp(this, 4));

        // Horizontally scrolling, because a chip carries a group's whole name
        // and three of them are wider than a phone.
        HorizontalScrollView chipScroller = new HorizontalScrollView(this);
        chipScroller.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chipScroller.addView(chips);
        header.addView(chipScroller);

        summary = new TextView(this);
        summary.setTextColor(Palette.secondaryText(this));
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        summary.setPadding(0, Palette.dp(this, 10), 0, Palette.dp(this, 6));
        header.addView(summary);

        // Outside the list rather than a header of it: a field rebuilt with
        // the rows it filters loses focus and takes the keyboard down on the
        // first letter typed.
        searchBox = searchBox();
        header.addView(searchBox);
        root.addView(header);

        list = new ListView(this);
        list.setDivider(null);
        list.setPadding(side, 0, side, side);
        list.setClipToPadding(false);
        adapter = new PeopleAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // A ListView's empty view has to be a sibling it can show in its
        // place, so it is built with the layout rather than on demand.
        emptyNotice = new TextView(this);
        emptyNotice.setTextColor(Palette.secondaryText(this));
        emptyNotice.setGravity(Gravity.CENTER_HORIZONTAL);
        emptyNotice.setPadding(side, Palette.dp(this, 24), side, 0);
        root.addView(emptyNotice, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        list.setEmptyView(emptyNotice);

        // Inset the root, not the list -- the list's own padding is the
        // screen's margin, this is what keeps the chips from drawing under
        // WhatsApp's action bar.
        insetBelowSystemBars(root);
        return root;
    }

    private EditText searchBox() {
        EditText search = new EditText(this);
        search.setHint("Search people");
        search.setSingleLine(true);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        search.setTextColor(Palette.primaryText(this));
        search.setHintTextColor(Palette.secondaryText(this));
        search.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Palette.ACCENT));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                query = text.toString().trim().toLowerCase(Locale.getDefault());
                showPeople();
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });
        return search;
    }

    private void insetBelowSystemBars(View content) {
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    /** Reads the group list once, then crosses whatever the screen opened on. */
    private void start() {
        drawHeader();
        final int mine = ++generation;
        reading = true;
        // Snapshot before the thread starts, not inside it: the picker this
        // very method may open runs on the main thread and rewrites the
        // selection, and a list being read on one thread while another adds
        // to it is a ConcurrentModificationException in the reader.
        final List<String> opened = new ArrayList<>(selected);
        new Thread(() -> {
            Context context = getApplicationContext();
            List<GroupMembers.Group> groups = GroupMembers.groups(context);
            main.post(() -> {
                if (mine != generation) {
                    return;
                }
                allGroups.clear();
                allGroups.addAll(groups);
                drawHeader();
                // Nothing to cross and nothing on the screen: asking straight
                // away is the whole of what this screen wants next.
                if (selected.isEmpty() && !offeredPicker && !groups.isEmpty()) {
                    offeredPicker = true;
                    showPicker();
                }
            });
            cross(mine, opened);
        }, "shared-members").start();
    }

    /** Crosses the current selection; safe to call from the main thread. */
    private void recross() {
        final int mine = ++generation;
        reading = true;
        drawHeader();
        List<String> gids = new ArrayList<>(selected);
        new Thread(() -> cross(mine, gids), "shared-members").start();
    }

    /**
     * One crossing, start to finish, off the main thread.
     *
     * Naming happens here too rather than per row: it opens two databases of
     * WhatsApp's, which is not something a ListView's bind can do.
     */
    private void cross(int generationAtStart, List<String> gids) {
        Context context = getApplicationContext();
        GroupMembers.Crossing crossing = GroupMembers.cross(context, gids);
        List<Person> found = name(context, crossing.shared);
        main.post(() -> {
            if (generationAtStart != generation) {
                return;
            }
            reading = false;
            failed = crossing.failed;
            anchorSize = crossing.anchorSize();
            people.clear();
            people.addAll(found);
            drawHeader();
            showPeople();
        });
    }

    /**
     * Labels each jid "number - name", the way the statistics screen does.
     *
     * Both halves, because a push name is whatever somebody calls themselves
     * and two people in a group of hundreds can easily choose the same one,
     * where the number is what actually identifies them. The number shown is
     * the phone jid's: a LID's digits are an internal identifier that means
     * nothing to anybody.
     */
    private static List<Person> name(Context context, List<String> jids) {
        Map<String, String> names = ParticipantNames.resolve(context, jids);
        List<Person> found = new ArrayList<>();
        for (String jid : jids) {
            String digits = GroupCrossing.digitsOf(LidJids.phoneJid(jid));
            String name = names.get(jid);
            found.add(new Person(jid, name == null ? digits : digits + " - " + name,
                    name == null ? "" : name.toLowerCase(Locale.getDefault())));
        }
        Collections.sort(found, new Comparator<Person>() {
            @Override
            public int compare(Person left, Person right) {
                // Named people first: a run of bare numbers at the top of the
                // list is the least useful thing the screen could lead with.
                boolean leftNamed = !left.sortKey.isEmpty();
                if (leftNamed != !right.sortKey.isEmpty()) {
                    return leftNamed ? -1 : 1;
                }
                int byName = left.sortKey.compareTo(right.sortKey);
                return byName != 0 ? byName : left.label.compareTo(right.label);
            }
        });
        return found;
    }

    /** The chips and the line under them; everything that is not the list. */
    private void drawHeader() {
        chips.removeAllViews();
        for (String gid : new ArrayList<>(selected)) {
            chips.addView(chip(nameOf(gid), true, () -> {
                selected.remove(gid);
                recross();
            }));
        }
        chips.addView(chip(selected.isEmpty() ? "Choose groups" : "Add group", false,
                this::showPicker));
        summary.setText(summaryText());
    }

    private String summaryText() {
        if (failed) {
            return "The message store could not be read.";
        }
        if (selected.isEmpty()) {
            return "Pick two or more groups to see who is in all of them.";
        }
        if (reading) {
            return "Reading members…";
        }
        if (selected.size() == 1) {
            // "other" because this device's owner is left out of every count
            // here, where WhatsApp's own member list counts them in.
            return anchorSize + (anchorSize == 1 ? " other member" : " other members")
                    + " · add a group to cross it with";
        }
        return people.size() + " of " + anchorSize + " are also in "
                + (selected.size() == 2 ? "the other group"
                        : "the other " + (selected.size() - 1) + " groups");
    }

    /** The name of a picked group, or its digits while the list is still loading. */
    private String nameOf(String gid) {
        for (GroupMembers.Group group : allGroups) {
            if (group.jid.equals(gid)) {
                return group.name;
            }
        }
        return GroupCrossing.digitsOf(gid);
    }

    /**
     * One group chip, or the one that adds another.
     *
     * A picked chip carries its own "remove" cross rather than a long press:
     * narrowing to nothing is the ordinary way to overshoot here, and undoing
     * it has to be as cheap as doing it.
     */
    private View chip(String text, boolean picked, Runnable onClick) {
        TextView view = new TextView(this);
        view.setText(picked ? text + "   ×" : "+  " + text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        view.setTextColor(Palette.primaryText(this));
        view.setBackground(Palette.chip(this, picked));
        view.setPadding(Palette.dp(this, 12), Palette.dp(this, 7),
                Palette.dp(this, 12), Palette.dp(this, 7));
        view.setClickable(true);
        view.setOnClickListener(ignored -> onClick.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(Palette.dp(this, 8));
        view.setLayoutParams(params);
        return view;
    }

    /** Applies the search field to the crossing's answer. */
    private void showPeople() {
        visible.clear();
        for (Person person : people) {
            if (query.isEmpty() || person.label.toLowerCase(Locale.getDefault()).contains(query)) {
                visible.add(person);
            }
        }
        adapter.notifyDataSetChanged();
        emptyNotice.setText(emptyText());
    }

    /**
     * What stands in for the list when there is nothing in it.
     *
     * Silent wherever the line above the list already accounts for the
     * emptiness -- before a group is picked, while a read is running, and
     * after one has failed -- so the screen says it once rather than twice.
     */
    private String emptyText() {
        if (failed || reading || selected.isEmpty()) {
            return "";
        }
        if (!query.isEmpty() && !people.isEmpty()) {
            return "Nobody here matches that.";
        }
        return selected.size() == 1
                ? "This group has nobody else in it." : "Nobody is in all of these.";
    }

    /** The people the crossing left, one row each. */
    private final class PeopleAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return visible.size();
        }

        @Override
        public Object getItem(int position) {
            return visible.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            // Built fresh rather than rebound: a row is a photo or a lettered
            // disc, which are different views, and swapping one for the other
            // inside a recycled row is more moving parts than a list this
            // short is worth. The ListView still only ever holds a screenful.
            Person person = visible.get(position);
            LinearLayout row = new LinearLayout(SharedMembersActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Palette.dp(SharedMembersActivity.this, 8),
                    0, Palette.dp(SharedMembersActivity.this, 8));
            // The row carries the click rather than the ListView, so the
            // ripple is drawn on the row the finger is actually on.
            row.setClickable(true);
            row.setBackground(Palette.rowRipple(SharedMembersActivity.this));
            row.setOnClickListener(view -> openProfile(person));
            row.addView(Avatar.of(SharedMembersActivity.this, avatars,
                    person.jid, person.label, ROW_AVATAR_DP));
            TextView label = new TextView(SharedMembersActivity.this);
            label.setText(person.label);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            label.setTextColor(Palette.primaryText(SharedMembersActivity.this));
            row.addView(label, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return row;
        }
    }

    /**
     * Hands somebody off to WhatsApp's own profile screen.
     *
     * The jid passed is the raw one the anchor group stores -- a LID for most
     * people now -- which is exactly what WhatsApp's own builder passes:
     * {@code putExtra(key, userJid.getRawString())}. Translating it to a
     * phone jid first would be the wrong helpfulness, since the profile is
     * addressed the same way the rest of the app addresses people.
     *
     * Everything about this can fail on a release that moved the screen, so
     * it fails out loud rather than silently: the row is the only thing lost,
     * and the log says which jid and which activity were tried.
     */
    private void openProfile(Person person) {
        try {
            Intent intent = new Intent().setClassName(getPackageName(), CONTACT_INFO_ACTIVITY);
            intent.putExtra(JID_EXTRA, person.jid);
            startActivity(intent);
            // The jid verbatim, because the two forms fail differently: a
            // phone jid that opens nothing is a moved screen, a LID that
            // opens nothing is a screen that will not take one.
            Log.i(TAG, "SharedMembersActivity: opened " + CONTACT_INFO_ACTIVITY
                    + " for " + person.jid);
        } catch (Throwable t) {
            Log.e(TAG, "SharedMembersActivity: could not open " + CONTACT_INFO_ACTIVITY
                    + " for " + person.jid, t);
            Toast.makeText(this, "WhatsApp would not open that profile.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * The group picker.
     *
     * A dialog of its own rather than a second activity: it is a selection
     * made against the screen behind it, and coming back to a screen that has
     * to rebuild itself would lose the search and the scroll position.
     *
     * Ticks carry the current selection, so it is equally the way to remove a
     * group; the chips are the shortcut for removing one.
     */
    private void showPicker() {
        if (allGroups.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("No groups")
                    .setMessage("WhatsApp's message store lists no groups on this device.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        final Set<String> ticked = new LinkedHashSet<>(selected);
        final List<GroupMembers.Group> shown = new ArrayList<>(allGroups);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int side = Palette.dp(this, 16);
        body.setPadding(side, side, side, 0);

        EditText search = new EditText(this);
        search.setHint("Search groups");
        search.setSingleLine(true);
        search.setTextColor(Palette.primaryText(this));
        search.setHintTextColor(Palette.secondaryText(this));
        search.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Palette.ACCENT));
        body.addView(search);

        ListView groupList = new ListView(this);
        groupList.setDivider(null);
        BaseAdapter groupAdapter = new GroupAdapter(shown, ticked);
        groupList.setAdapter(groupAdapter);
        body.addView(groupList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, pickerListHeight()));

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                String needle = text.toString().trim().toLowerCase(Locale.getDefault());
                shown.clear();
                for (GroupMembers.Group group : allGroups) {
                    if (needle.isEmpty()
                            || group.name.toLowerCase(Locale.getDefault()).contains(needle)) {
                        shown.add(group);
                    }
                }
                groupAdapter.notifyDataSetChanged();
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Groups to cross")
                .setView(body)
                .setPositiveButton("Done", (ignored, which) -> apply(ticked))
                .setNegativeButton("Cancel", null)
                .create();
        if (dialog.getWindow() != null) {
            // Resize rather than pan, so the dialog is laid out in what is
            // left above the keyboard instead of being slid up out of it.
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
    }

    /**
     * How tall the picker's list may be.
     *
     * Measured against the screen rather than fixed, because the search field
     * above it puts the keyboard up and the keyboard takes the bottom half:
     * at a fixed 360dp the dialog's own Done button ends up *behind* the
     * keyboard and cannot be tapped at all, which is exactly the state
     * searching for a group leaves the picker in. Found by driving it, not by
     * reading it.
     */
    private int pickerListHeight() {
        int quarter = getResources().getDisplayMetrics().heightPixels / 4;
        return Math.max(Palette.dp(this, 160), Math.min(Palette.dp(this, 320), quarter));
    }

    /**
     * Takes the picker's ticks as the new selection.
     *
     * Groups already picked keep their order, and the first of them stays
     * first: it is the one the rest filter, and the one whose spelling of a
     * member the names and photos are looked up under.
     */
    private void apply(Set<String> ticked) {
        List<String> next = new ArrayList<>();
        for (String gid : selected) {
            if (ticked.contains(gid)) {
                next.add(gid);
            }
        }
        for (GroupMembers.Group group : allGroups) {
            if (ticked.contains(group.jid) && !next.contains(group.jid)) {
                next.add(group.jid);
            }
        }
        selected.clear();
        selected.addAll(next);
        recross();
    }

    /** The picker's rows: a group, its size, and whether it is in the crossing. */
    private final class GroupAdapter extends BaseAdapter {
        private final List<GroupMembers.Group> shown;
        private final Set<String> ticked;

        GroupAdapter(List<GroupMembers.Group> shown, Set<String> ticked) {
            this.shown = shown;
            this.ticked = ticked;
        }

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            GroupMembers.Group group = shown.get(position);
            CheckBox box = convertView instanceof CheckBox
                    ? (CheckBox) convertView : new CheckBox(SharedMembersActivity.this);
            // Cleared before the text is set: a recycled box carries the
            // previous row's listener, which would tick that group instead.
            box.setOnCheckedChangeListener(null);
            box.setText(group.name + "  ·  " + group.members);
            box.setTextColor(Palette.primaryText(SharedMembersActivity.this));
            box.setPadding(Palette.dp(SharedMembersActivity.this, 8),
                    Palette.dp(SharedMembersActivity.this, 10),
                    0, Palette.dp(SharedMembersActivity.this, 10));
            Palette.paintCheckable(box);
            box.setChecked(ticked.contains(group.jid));
            box.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    ticked.add(group.jid);
                } else {
                    ticked.remove(group.jid);
                }
            });
            return box;
        }
    }
}
