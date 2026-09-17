package com.smali_generator.ui;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.smali_generator.abprops.AbProp;
import com.smali_generator.abprops.AbPropStore;
import com.smali_generator.abprops.AbPropTable;
import com.smali_generator.db.PatchDb;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Browses the app's A/B properties and overrides them.
 *
 * A property is a number and nothing else -- no name ships in the APK -- so
 * there are twenty thousand of them and no way to read down the list. What makes it
 * usable is the other two columns: what the build ships as the default, and
 * what the app's own accessors have actually been answering. A property whose
 * live value differs from its shipped default is one Meta has turned on for
 * this account, and a property the app has never asked for is one no code path
 * reached. Those two filters are where a feature worth finding shows up.
 *
 * Nothing here is written down about any particular property. The defaults come
 * out of the running app, and the list of what was read comes from the hook.
 *
 * A {@link ListView} rather than rows in a ScrollView for the obvious reason:
 * twenty thousand of them.
 *
 * An override that broke the app is one this screen has to explain rather than
 * only undo, which is what the held-back banner and its filter are for: the
 * launch after a failed start keeps them but stops installing them, and without
 * something saying so the screen would list overrides that are quietly doing
 * nothing.
 */
public class AbPropsActivity extends Activity {

    private static final int SIDE_PADDING_DP = 20;

    /** Menu item ids. Any distinct constants do; nothing else looks them up. */
    private static final int MENU_RESET = 1;
    private static final int MENU_SELECT = 2;

    /** What the list is narrowed to. The counts are the point of the labels:
     *  they say how much of the haystack each one takes away. */
    private enum Filter {
        ALL("All"),
        SEEN("Read by app"),
        CHANGED("Changed"),
        OVERRIDDEN("Overridden"),
        /** Only drawn when there are any, so the usual screen keeps four. */
        HELD_BACK("Held back");

        private final String title;

        Filter(String title) {
            this.title = title;
        }
    }

    private final List<AbProp> all = new ArrayList<>();
    private final List<AbProp> visible = new ArrayList<>();

    /** What each accessor last answered of its own accord, this run and before. */
    private Map<Integer, Object> seen = new HashMap<>();

    private Filter filter = Filter.ALL;

    /** Whether a tap picks a property rather than opening its editor. */
    private boolean selecting;

    /** Picked properties, by id. Survives a filter change, so a selection built
     *  across two filters is still one selection. */
    private final Set<Integer> selected = new HashSet<>();

    /** Types the list is narrowed to. Empty means all of them. */
    private final EnumSet<AbProp.Type> types = EnumSet.noneOf(AbProp.Type.class);

    private View heldBackBanner;
    private TextView heldBackText;
    private LinearLayout chipRow;
    private LinearLayout typeChipRow;
    private EditText searchBox;
    private TextView countLine;
    private TextView notice;
    private View selectionBar;
    private TextView selectionCount;
    private TextView emptyNotice;
    private PropAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("A/B properties");
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }
        PatchDb.init(getApplicationContext());
        // The screen is reachable with the hook switched off, so it cannot
        // assume the hook's own load() has run.
        AbPropStore.load();
        seen = AbPropStore.observations();
        // After reading, and off this thread: what is in memory is already
        // merged above, so nothing on screen waits for a few thousand inserts.
        new Thread(AbPropStore::flushObservations, "ab-props-flush").start();

        all.clear();
        all.addAll(AbPropTable.load(AbPropStore.owner(), seen));

        setContentView(buildContent());
        installOverflowButton();
        applyFilter();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * The three dots, drawn as an action-bar custom view rather than declared
     * as an options menu.
     *
     * The framework overflow cannot be used from a patcher screen. WhatsApp
     * wraps every activity's {@link android.view.Window.Callback} -- ours
     * included, since they run in its process -- with a Kotlin class whose
     * parameters are checked non-null, and the framework's own overflow path
     * calls {@code onMenuOpened(featureId, null)}. Opening the menu took the
     * app down in WhatsApp's code, from a stack with nothing of this patch in
     * it. A view of our own reaches none of that.
     */
    private void installOverflowButton() {
        ActionBar bar = getActionBar();
        if (bar == null) {
            return;
        }
        TextView dots = new TextView(this);
        dots.setText("\u22EE");
        dots.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        dots.setTypeface(Typeface.DEFAULT_BOLD);
        dots.setTextColor(Palette.primaryText(this));
        dots.setGravity(Gravity.CENTER);
        dots.setPadding(dp(18), 0, dp(18), 0);
        dots.setBackground(Palette.rowRipple(this));
        dots.setContentDescription("More options");
        dots.setOnClickListener(this::showOverflow);
        bar.setCustomView(dots, new ActionBar.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END | Gravity.CENTER_VERTICAL));
        bar.setDisplayShowCustomEnabled(true);
    }

    /**
     * Built fresh on every tap, which is how both items stay honest about the
     * state they are about to act on without an invalidate anywhere.
     *
     * A {@link PopupMenu} rather than the activity's own menu: it puts up a
     * window of its own and never asks the activity's window callback, which is
     * the thing that cannot be called here.
     */
    private void showOverflow(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor, Gravity.END);
        popup.getMenu().add(Menu.NONE, MENU_RESET, 0, "Reset everything")
                .setEnabled(AbPropStore.storedCount() > 0);
        popup.getMenu().add(Menu.NONE, MENU_SELECT, 1,
                selecting ? "Stop selecting" : "Select multiple");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_RESET) {
                confirmClearAll();
                return true;
            }
            if (item.getItemId() == MENU_SELECT) {
                setSelecting(!selecting);
                return true;
            }
            return false;
        });
        popup.show();
    }

    /**
     * Turns tapping a row from "edit this one" into "pick this one".
     *
     * Leaving takes the picks with it. A selection that survived out of sight
     * would be one the next action applied to without anything on screen
     * having said so.
     */
    private void setSelecting(boolean on) {
        selecting = on;
        if (!on) {
            selected.clear();
        }
        selectionBar.setVisibility(on ? View.VISIBLE : View.GONE);
        refreshSelectionCount();
        adapter.notifyDataSetChanged();
    }

    private void refreshSelectionCount() {
        selectionCount.setText(selected.isEmpty() ? "Tap the ones to change"
                : selected.size() == 1 ? "1 selected" : selected.size() + " selected");
    }

    /**
     * Applies one answer to everything picked, then leaves selection mode.
     *
     * Leaving is the end of the gesture rather than a separate step: the picks
     * have been spent, and a selection left standing after it has been acted on
     * is one the next tap would act on again.
     */
    private void applyToSelection(String value) {
        if (selected.isEmpty()) {
            Toast.makeText(this, "Nothing selected", Toast.LENGTH_SHORT).show();
            return;
        }
        int applied = 0;
        int skipped = 0;
        for (AbProp prop : all) {
            if (!selected.contains(prop.id)) {
                continue;
            }
            if (value == null) {
                AbPropStore.set(prop.id, prop.type, null, false);
                applied++;
            } else if (prop.type == AbProp.Type.BOOL) {
                AbPropStore.set(prop.id, prop.type, value, false);
                applied++;
            } else {
                skipped++;
            }
        }
        String what = value == null ? " reset" : " set to " + value;
        Toast.makeText(this, applied + (applied == 1 ? " property" : " properties") + what
                + (skipped == 0 ? "" : ", " + skipped + " not boolean and left alone"),
                Toast.LENGTH_LONG).show();
        setSelecting(false);
        applyFilter();
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        int side = dp(SIDE_PADDING_DP);
        header.setPadding(side, dp(12), side, 0);
        header.addView(heldBackBanner());
        header.addView(notice());
        header.addView(chips());
        header.addView(typeChips());
        header.addView(searchBox());
        header.addView(tools());
        root.addView(header);

        root.addView(selectionBar());

        root.addView(list(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(bottomBar());
        insetBelowSystemBars(root);
        return root;
    }

    /**
     * What a failed start left behind, and the one tap that undoes it.
     *
     * The overrides are kept rather than dropped, so this is the only thing
     * that says they are no longer in force -- without it the screen would show
     * a set of overrides that are quietly doing nothing, which is the failure
     * this whole feature exists to make visible.
     */
    private View heldBackBanner() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(10));

        heldBackText = new TextView(this);
        heldBackText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        heldBackText.setTextColor(Palette.ACCENT);
        row.addView(heldBackText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button restore = new Button(this);
        restore.setText("Restore");
        restore.setAllCaps(false);
        restore.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        restore.setTypeface(Typeface.DEFAULT_BOLD);
        restore.setTextColor(Palette.ACCENT);
        restore.setStateListAnimator(null);
        restore.setBackground(Palette.rowRipple(this));
        restore.setMinWidth(0);
        restore.setMinimumWidth(0);
        restore.setMinHeight(0);
        restore.setMinimumHeight(dp(40));
        restore.setPadding(dp(12), dp(6), dp(12), dp(6));
        restore.setOnClickListener(view -> {
            AbPropStore.restoreHeldBack();
            // Back to the whole list: the held-back filter is about to be empty,
            // and a screen showing nothing with no explanation is worse than one
            // showing everything.
            filter = Filter.ALL;
            applyFilter();
        });
        row.addView(restore);

        heldBackBanner = row;
        return row;
    }

    private void refreshHeldBackBanner() {
        int count = AbPropStore.heldBackCount();
        heldBackBanner.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        heldBackText.setText((count == 1 ? "1 override was" : count + " overrides were")
                + " held back after WhatsApp failed to start.");
    }

    /**
     * Shown only when nothing has read a property in this process.
     *
     * That is what an unhooked app looks like from here: the defaults are read
     * off the app's own properties object, and the accessor path is the only
     * place one goes past.
     */
    private View notice() {
        notice = new TextView(this);
        notice.setText("Nothing has read a property in this process. Switch on “A/B property "
                + "overrides” on the previous screen and restart WhatsApp to see the full list.");
        notice.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        notice.setTextColor(Palette.ACCENT);
        notice.setPadding(0, 0, 0, dp(12));
        notice.setVisibility(AbPropStore.owner() == null ? View.VISIBLE : View.GONE);
        return notice;
    }

    private View chips() {
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        for (Filter option : Filter.values()) {
            chipRow.addView(chip(view -> {
                filter = option;
                applyFilter();
            }), chipParams());
        }
        return chipScroller(chipRow, dp(8));
    }

    /**
     * The type row, which narrows the state row rather than replacing it.
     *
     * A row of its own, and more than one at a time, because the question worth
     * asking is a pair: the booleans the app has read, the ints that differ
     * from what the build ships. One row of chips could express neither.
     *
     * Nothing selected means every type, which is the same screen as before
     * this row existed.
     */
    private View typeChips() {
        typeChipRow = new LinearLayout(this);
        typeChipRow.setOrientation(LinearLayout.HORIZONTAL);
        for (AbProp.Type type : AbProp.Type.values()) {
            typeChipRow.addView(chip(view -> {
                if (!types.remove(type)) {
                    types.add(type);
                }
                applyFilter();
            }), chipParams());
        }
        return chipScroller(typeChipRow, dp(10));
    }

    private TextView chip(View.OnClickListener onClick) {
        TextView chip = new TextView(this);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setPadding(dp(14), dp(7), dp(14), dp(7));
        chip.setOnClickListener(onClick);
        return chip;
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(8);
        return params;
    }

    /** Chips with their counts do not fit a phone's width, and a chip that is
     *  half off the screen is one nobody knows is there. */
    private View chipScroller(View row, int bottomPadding) {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(row);
        scroller.setPadding(0, 0, 0, bottomPadding);
        return scroller;
    }

    /**
     * Each chip says what picking it would leave, given the other row.
     *
     * A chip counts under the whole of the opposite row but not under its own,
     * so choosing one type does not empty the counts on the other three -- the
     * numbers stay a description of the data rather than of the last tap.
     */
    private void paintChips() {
        Filter[] options = Filter.values();
        for (int index = 0; index < options.length; index++) {
            TextView chip = (TextView) chipRow.getChildAt(index);
            int count = 0;
            for (AbProp prop : all) {
                if (matchesFilter(prop, options[index]) && matchesType(prop)) {
                    count++;
                }
            }
            // The held-back chip is a symptom, not a category: on a screen that
            // has never had a failed start it should not be there at all.
            if (options[index] == Filter.HELD_BACK && count == 0
                    && AbPropStore.heldBackCount() == 0) {
                chip.setVisibility(View.GONE);
                continue;
            }
            chip.setVisibility(View.VISIBLE);
            paintChip(chip, options[index].title, count, options[index] == filter);
        }

        AbProp.Type[] allTypes = AbProp.Type.values();
        for (int index = 0; index < allTypes.length; index++) {
            int count = 0;
            for (AbProp prop : all) {
                if (prop.type == allTypes[index] && matchesFilter(prop, filter)) {
                    count++;
                }
            }
            paintChip((TextView) typeChipRow.getChildAt(index),
                    allTypes[index].label(), count, types.contains(allTypes[index]));
        }
    }

    private void paintChip(TextView chip, String title, int count, boolean selected) {
        chip.setText(title + "  " + count);
        chip.setTextColor(selected ? Palette.ACCENT : Palette.secondaryText(this));
        chip.setBackground(Palette.chip(this, selected));
    }

    /**
     * Matches anything the row shows: the id, the shipped default, what the app
     * last answered, and any override.
     *
     * Deliberately all four rather than the value alone. A row reading "default
     * false \u00b7 app said true" answers to both words, which is the only rule
     * that does not need explaining to whoever typed one of them -- and the
     * state and type chips are there to narrow what it returns.
     */
    private boolean matchesSearch(AbProp prop, String needle) {
        if (needle.isEmpty() || prop.haystack.contains(needle)) {
            return true;
        }
        Object live = seen.get(prop.id);
        if (live != null && String.valueOf(live).toLowerCase(Locale.US).contains(needle)) {
            return true;
        }
        AbPropStore.Override override = AbPropStore.overrides().get(prop.id);
        return override != null && override.text.toLowerCase(Locale.US).contains(needle);
    }

    /** No type chosen means every type: the screen this row was added to. */
    private boolean matchesType(AbProp prop) {
        return types.isEmpty() || types.contains(prop.type);
    }

    private View searchBox() {
        searchBox = new EditText(this);
        searchBox.setHint("Search id or value");
        searchBox.setSingleLine(true);
        // Text, not digits. A number pad cannot type "false", and matching
        // values is half of what this box is for.
        searchBox.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        searchBox.setTextColor(Palette.primaryText(this));
        searchBox.setHintTextColor(Palette.secondaryText(this));
        searchBox.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Palette.ACCENT));
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });
        return searchBox;
    }

    /** What the filters above have left, and how much of it this patch is
     *  holding. The actions that used to sit here are in the overflow menu:
     *  the header already carries two rows of chips and a search box. */
    private View tools() {
        countLine = new TextView(this);
        countLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        countLine.setTextColor(Palette.secondaryText(this));
        countLine.setPadding(0, dp(4), 0, dp(4));
        return countLine;
    }

    /**
     * The bar that acts on a selection, shown only while one is being made.
     *
     * Three buttons and no value field: a selection can hold properties of
     * different types, and the only answers that mean the same thing to all of
     * them are true, false and "stop overriding it". Anything in the selection
     * that is not a boolean is left alone by true and false, and the toast
     * afterwards says how many that was rather than failing quietly.
     */
    private View selectionBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int side = dp(SIDE_PADDING_DP);
        row.setPadding(side, dp(4), side, dp(4));

        selectionCount = new TextView(this);
        selectionCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        selectionCount.setTypeface(Typeface.DEFAULT_BOLD);
        selectionCount.setTextColor(Palette.ACCENT);
        row.addView(selectionCount, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(flatButton("Reset", view -> applyToSelection(null)));
        row.addView(flatButton("true", view -> applyToSelection("true")));
        row.addView(flatButton("false", view -> applyToSelection("false")));

        selectionBar = row;
        row.setVisibility(View.GONE);
        return row;
    }

    private Button flatButton(String label, View.OnClickListener onClick) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(Palette.ACCENT);
        // Flat and text-only: the green pill on this screen is the restart, and
        // there should only be one thing on a screen that looks like the action.
        button.setStateListAnimator(null);
        button.setBackground(Palette.rowRipple(this));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(dp(40));
        button.setPadding(dp(10), dp(6), dp(10), dp(6));
        button.setOnClickListener(onClick);
        return button;
    }

    private View list() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);

        emptyNotice = new TextView(this);
        emptyNotice.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        emptyNotice.setTextColor(Palette.secondaryText(this));
        emptyNotice.setPadding(dp(SIDE_PADDING_DP), dp(16), dp(SIDE_PADDING_DP), 0);
        block.addView(emptyNotice);

        adapter = new PropAdapter();
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setPadding(dp(SIDE_PADDING_DP), dp(4), dp(SIDE_PADDING_DP), dp(4));
        list.setClipToPadding(false);
        block.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return block;
    }

    /**
     * The restart sits beside the footer rather than under it: the sentence
     * next to it is the reason to press it.
     */
    private View bottomBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int side = dp(SIDE_PADDING_DP);
        row.setPadding(side, dp(8), side, dp(12));

        TextView footer = new TextView(this);
        footer.setText("A change applies to the next read. Restart so it reaches what the app "
                + "has already cached.");
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        footer.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        footer.setTextColor(Palette.secondaryText(this));
        footer.setPadding(0, 0, dp(12), 0);
        row.addView(footer, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(Restart.button(this, "Restart"));
        return row;
    }

    private boolean matchesFilter(AbProp prop, Filter option) {
        switch (option) {
            case SEEN:
                if (seen.containsKey(prop.id)) {
                    return true;
                }
                // A property the app is reading right now, but whose answer is
                // this patch's rather than its own -- so nothing recorded a
                // value for it, and it would otherwise look unread.
                AbPropStore.Override served = AbPropStore.overrides().get(prop.id);
                return served != null && served.served;
            case CHANGED:
                Object live = seen.get(prop.id);
                return live != null && prop.defaultValue != null && !live.equals(prop.defaultValue);
            case OVERRIDDEN:
                AbPropStore.Override active = AbPropStore.overrides().get(prop.id);
                return active != null && !active.heldBack;
            case HELD_BACK:
                AbPropStore.Override kept = AbPropStore.overrides().get(prop.id);
                return kept != null && kept.heldBack;
            default:
                return true;
        }
    }

    private void applyFilter() {
        String needle = searchBox == null ? ""
                : searchBox.getText().toString().trim().toLowerCase(Locale.US);
        visible.clear();
        for (AbProp prop : all) {
            if (matchesFilter(prop, filter) && matchesType(prop)
                    && matchesSearch(prop, needle)) {
                visible.add(prop);
            }
        }
        adapter.notifyDataSetChanged();
        paintChips();
        refreshHeldBackBanner();

        int overridden = AbPropStore.count();
        countLine.setText(visible.size() + " shown · "
                + (overridden == 0 ? "none overridden"
                : overridden == 1 ? "1 overridden" : overridden + " overridden"));

        emptyNotice.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        emptyNotice.setText(all.isEmpty()
                ? "No properties to show yet."
                : types.isEmpty() ? "Nothing matches that."
                : "Nothing matches that, in the types you picked.");
    }

    private void confirmClearAll() {
        int count = AbPropStore.storedCount();
        if (count == 0) {
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Clear all overrides?")
                .setMessage((count == 1 ? "The one overridden property"
                        : "All " + count + " overridden properties")
                        + " will go back to whatever the app says.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear all", (ignored, which) -> {
                    AbPropStore.clearAll();
                    applyFilter();
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
        Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (neutral != null) {
            neutral.setTextColor(Palette.ACCENT);
        }
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (negative != null) {
            negative.setTextColor(Palette.secondaryText(this));
        }
    }

    private void edit(AbProp prop) {
        if (prop.type == AbProp.Type.BOOL) {
            editBoolean(prop);
        } else {
            editValue(prop);
        }
    }

    /**
     * Three choices rather than a switch: "no override" has to stay tellable
     * from "overridden to what it already was", which is the difference between
     * a property this patch is holding and one it is not.
     *
     * Built by hand rather than with setSingleChoiceItems because the "one
     * launch" box has to sit under the choices, and a list dialog has no room
     * for anything but its list.
     */
    private void editBoolean(AbProp prop) {
        AbPropStore.Override current = AbPropStore.overrides().get(prop.id);
        String[] choices = {"Leave it to the app (" + effective(prop) + ")", "true", "false"};
        int checked = current == null ? 0 : "true".equalsIgnoreCase(current.text) ? 1 : 2;

        LinearLayout frame = dialogFrame();
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        for (int index = 0; index < choices.length; index++) {
            RadioButton option = new RadioButton(this);
            option.setId(index + 1);
            option.setText(choices[index]);
            option.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            option.setTextColor(Palette.primaryText(this));
            option.setPadding(dp(8), dp(6), 0, dp(6));
            Palette.paintCheckable(option);
            group.addView(option);
        }
        group.check(checked + 1);
        frame.addView(group);
        CheckBox once = onceBox(current);
        frame.addView(once);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(String.valueOf(prop.id))
                .setView(frame)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Set", (ignored, which) -> {
                    int chosen = group.getCheckedRadioButtonId() - 1;
                    AbPropStore.set(prop.id, prop.type,
                            chosen <= 0 ? null : choices[chosen], once.isChecked());
                    applyFilter();
                })
                .create();
        dialog.show();
        paintDialog(dialog);
    }

    /**
     * The opt-in that makes trying an unknown property cheap: it is forgotten
     * the moment it is installed, so the run being watched has it and the next
     * one does not, whatever happens in between.
     */
    private CheckBox onceBox(AbPropStore.Override current) {
        CheckBox box = new CheckBox(this);
        box.setText("Only for the next launch");
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        box.setTextColor(Palette.secondaryText(this));
        box.setPadding(dp(8), dp(10), 0, 0);
        box.setChecked(current != null && current.once);
        Palette.paintCheckable(box);
        return box;
    }

    private LinearLayout dialogFrame() {
        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        int side = dp(SIDE_PADDING_DP);
        frame.setPadding(side, dp(8), side, 0);
        return frame;
    }

    private void editValue(AbProp prop) {
        AbPropStore.Override current = AbPropStore.overrides().get(prop.id);
        EditText input = new EditText(this);
        input.setText(current == null ? effective(prop) : current.text);
        input.setSelectAllOnFocus(true);
        input.setTextColor(Palette.primaryText(this));
        input.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Palette.ACCENT));
        if (prop.type == AbProp.Type.INT) {
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        } else if (prop.type == AbProp.Type.FLOAT) {
            input.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        }
        LinearLayout frame = dialogFrame();
        frame.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        CheckBox once = onceBox(current);
        frame.addView(once);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(String.valueOf(prop.id))
                .setMessage(prop.type.label() + " · default " + prop.defaultText())
                .setView(frame)
                .setNeutralButton("Leave it to the app", (ignored, which) -> {
                    AbPropStore.set(prop.id, prop.type, null, false);
                    applyFilter();
                })
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Set", null)
                .create();
        dialog.show();
        paintDialog(dialog);
        // Wired after show() so that a value the type cannot read leaves the
        // dialog open with the text still in it, rather than closing over it.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String text = input.getText().toString();
            if (prop.type.parse(text) == null) {
                Toast.makeText(this, "Not a " + prop.type.label(), Toast.LENGTH_SHORT).show();
                return;
            }
            AbPropStore.set(prop.id, prop.type, text, once.isChecked());
            applyFilter();
            dialog.dismiss();
        });
    }

    /** What the app would answer without this patch: what it last answered, or
     *  failing that what the build ships. */
    private String effective(AbProp prop) {
        Object live = seen.get(prop.id);
        return AbProp.format(live != null ? live : prop.defaultValue);
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

    private final class PropAdapter extends BaseAdapter {

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
            return visible.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView == null ? newRow() : convertView;
            AbProp prop = visible.get(position);

            TextView id = row.findViewById(ID_ID);
            TextView detail = row.findViewById(DETAIL_ID);
            TextView status = row.findViewById(STATUS_ID);

            id.setText(String.valueOf(prop.id));

            Object live = seen.get(prop.id);
            StringBuilder line = new StringBuilder(prop.type.label());
            line.append(" · default ").append(prop.defaultText());
            if (live != null) {
                line.append(" · app said ").append(AbProp.format(live));
            }
            detail.setText(line.toString());

            AbPropStore.Override override = AbPropStore.overrides().get(prop.id);
            if (override != null && override.heldBack) {
                status.setText("held back after a failed start \u00b7 was " + override.text);
                status.setVisibility(View.VISIBLE);
            } else if (override != null) {
                status.setText(override.value == null
                        ? "“" + override.text + "” is not a " + override.type.label()
                        + ", so it is ignored"
                        // Whether the app has actually asked is the whole
                        // difference between an override that is doing
                        // something and one that is sitting there.
                        : override.served
                        ? "forced " + override.text + " · the app has read it"
                        : "forced " + override.text + " · not read yet");
                status.setVisibility(View.VISIBLE);
            } else if (live != null && prop.defaultValue != null && !live.equals(prop.defaultValue)) {
                status.setText("changed from the shipped default");
                status.setVisibility(View.VISIBLE);
            } else {
                status.setVisibility(View.GONE);
            }

            CheckBox box = row.findViewById(SELECT_ID);
            box.setVisibility(selecting ? View.VISIBLE : View.GONE);
            box.setChecked(selected.contains(prop.id));

            row.setOnClickListener(view -> {
                if (!selecting) {
                    edit(prop);
                    return;
                }
                boolean picked = !selected.remove(prop.id);
                if (picked) {
                    selected.add(prop.id);
                }
                // The box is the one this bind just set, so it is this row's --
                // cheaper than rebinding the list to change one tick.
                box.setChecked(picked);
                refreshSelectionCount();
            });
            return row;
        }

        private View newRow() {
            LinearLayout row = new LinearLayout(AbPropsActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            row.setBackground(Palette.rowRipple(AbPropsActivity.this));

            LinearLayout text = new LinearLayout(AbPropsActivity.this);
            text.setOrientation(LinearLayout.VERTICAL);
            row.addView(text, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            CheckBox box = new CheckBox(AbPropsActivity.this);
            box.setId(SELECT_ID);
            // The whole row is the target; a box that took the tap itself would
            // leave half of each row doing nothing.
            box.setClickable(false);
            box.setFocusable(false);
            Palette.paintCheckable(box);
            row.addView(box);

            TextView id = new TextView(AbPropsActivity.this);
            id.setId(ID_ID);
            // Monospaced because the only thing to compare one row to the next
            // by is a five-digit number.
            id.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            id.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            id.setTextColor(Palette.primaryText(AbPropsActivity.this));
            text.addView(id);

            TextView detail = new TextView(AbPropsActivity.this);
            detail.setId(DETAIL_ID);
            detail.setSingleLine(true);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            detail.setTextColor(Palette.secondaryText(AbPropsActivity.this));
            text.addView(detail);

            TextView status = new TextView(AbPropsActivity.this);
            status.setId(STATUS_ID);
            status.setSingleLine(true);
            status.setEllipsize(android.text.TextUtils.TruncateAt.END);
            status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            status.setTypeface(Typeface.DEFAULT_BOLD);
            status.setTextColor(Palette.ACCENT);
            text.addView(status);
            return row;
        }
    }

    // Ids for views built in code. Any non-zero constants work; these are
    // looked up only within a row this class built itself.
    private static final int ID_ID = 0x7E000011;
    private static final int SELECT_ID = 0x7E000014;
    private static final int DETAIL_ID = 0x7E000012;
    private static final int STATUS_ID = 0x7E000013;
}
