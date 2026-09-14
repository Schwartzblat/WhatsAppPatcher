package com.smali_generator.ui;

import android.app.Activity;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.smali_generator.db.LidJids;
import com.smali_generator.db.PatchDb;
import com.smali_generator.db.WhatsAppChats;
import com.smali_generator.patches.ReadReceipts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Chooses which chats read receipts are held back from.
 *
 * A scope and one list of chats, rather than two lists: "only these" and
 * "everyone except these" are the same picks read in opposite directions, so
 * switching between a blocklist and an allowlist keeps them.
 *
 * Everything is written as it is touched; there is no save button and no
 * restart. The hook reads the scope and the list per receipt, so a change here
 * is live -- which is the opposite of the switches on the previous screen, and
 * the reason the footer says so.
 *
 * The list is a {@link ListView} rather than rows in a ScrollView because it
 * is as long as the user's contact list; recycling is the whole point.
 */
public class ChatPickerActivity extends Activity {

    private static final int SIDE_PADDING_DP = 20;

    private final List<WhatsAppChats.Chat> allChats = new ArrayList<>();
    private final List<WhatsAppChats.Chat> visibleChats = new ArrayList<>();

    private ChatAdapter adapter;
    private TextView scopeCaption;
    private View searchBox;
    private View listSection;
    private TextView emptyNotice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Read receipts");
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }
        PatchDb.init(getApplicationContext());
        // A chat first opened since this process started has a LID the receipt
        // path will translate but the cached map has never seen, and picking it
        // here would then do nothing until a restart.
        LidJids.invalidate();
        loadChats();
        setContentView(buildContent());
        applyScope(ReadReceipts.scope());
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
     * Already-picked chats first, then the rest.
     *
     * Ordered once, on open, rather than kept ordered: a list that reshuffled
     * under the finger every time a box was ticked would be unusable. A pick
     * whose contact has since gone is carried in from the selection itself, so
     * that it can still be found and undone.
     */
    private void loadChats() {
        List<WhatsAppChats.Chat> loaded = WhatsAppChats.load(getApplicationContext());
        Set<String> selected = PatchDb.selectedChats(ReadReceipts.FEATURE);

        List<WhatsAppChats.Chat> picked = new ArrayList<>();
        List<WhatsAppChats.Chat> rest = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (WhatsAppChats.Chat chat : loaded) {
            seen.add(chat.jid);
            (selected.contains(chat.jid) ? picked : rest).add(chat);
        }
        for (String jid : selected) {
            if (!seen.contains(jid)) {
                picked.add(WhatsAppChats.unknown(jid));
            }
        }
        allChats.clear();
        allChats.addAll(picked);
        allChats.addAll(rest);
        visibleChats.clear();
        visibleChats.addAll(allChats);
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        int side = dp(SIDE_PADDING_DP);
        header.setPadding(side, dp(12), side, 0);
        header.addView(scopeChooser());
        searchBox = searchBox();
        header.addView(searchBox);
        root.addView(header);

        listSection = chatList();
        root.addView(listSection, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(footer());
        insetBelowSystemBars(root);
        return root;
    }

    private View scopeChooser() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);

        TextView heading = new TextView(this);
        heading.setText("HIDE RECEIPTS FROM");
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setLetterSpacing(0.10f);
        heading.setTextColor(Palette.ACCENT);
        block.addView(heading);

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        group.setPadding(0, dp(6), 0, 0);
        ReadReceipts.Scope current = ReadReceipts.scope();
        for (ReadReceipts.Scope scope : ReadReceipts.Scope.values()) {
            RadioButton option = new RadioButton(this);
            option.setId(scope.ordinal() + 1);
            option.setText(scope.title());
            option.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            option.setTextColor(Palette.primaryText(this));
            option.setPadding(dp(8), dp(6), 0, dp(6));
            Palette.paintCheckable(option);
            group.addView(option);
            if (scope == current) {
                group.check(option.getId());
            }
        }
        group.setOnCheckedChangeListener((ignored, checkedId) ->
                onScopeChosen(ReadReceipts.Scope.values()[checkedId - 1]));
        block.addView(group);

        scopeCaption = new TextView(this);
        scopeCaption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        scopeCaption.setTextColor(Palette.secondaryText(this));
        scopeCaption.setPadding(0, dp(2), 0, dp(12));
        block.addView(scopeCaption);
        return block;
    }

    private View searchBox() {
        EditText search = new EditText(this);
        search.setHint("Search chats");
        search.setSingleLine(true);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        search.setTextColor(Palette.primaryText(this));
        search.setHintTextColor(Palette.secondaryText(this));
        search.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Palette.ACCENT));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                filter(text.toString());
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });
        return search;
    }

    private View chatList() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);

        emptyNotice = new TextView(this);
        emptyNotice.setText("No chats to show. WhatsApp's contact list could not be read.");
        emptyNotice.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        emptyNotice.setTextColor(Palette.secondaryText(this));
        emptyNotice.setPadding(dp(SIDE_PADDING_DP), dp(16), dp(SIDE_PADDING_DP), 0);
        emptyNotice.setVisibility(allChats.isEmpty() ? View.VISIBLE : View.GONE);
        block.addView(emptyNotice);

        adapter = new ChatAdapter();
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setPadding(dp(SIDE_PADDING_DP), dp(4), dp(SIDE_PADDING_DP), dp(4));
        list.setClipToPadding(false);
        block.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return block;
    }

    private View footer() {
        TextView footer = new TextView(this);
        footer.setText("Takes effect straight away \u2014 no restart needed.");
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        footer.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        footer.setTextColor(Palette.secondaryText(this));
        int side = dp(SIDE_PADDING_DP);
        footer.setPadding(side, dp(10), side, dp(14));
        return footer;
    }

    private void onScopeChosen(ReadReceipts.Scope scope) {
        ReadReceipts.setScope(scope);
        applyScope(scope);
    }

    /** "Every chat" has nothing to pick, so the picker goes away rather than sitting there inert. */
    private void applyScope(ReadReceipts.Scope scope) {
        scopeCaption.setText(scope.caption());
        int visibility = scope == ReadReceipts.Scope.EVERYONE ? View.GONE : View.VISIBLE;
        searchBox.setVisibility(visibility);
        listSection.setVisibility(visibility);
    }

    private void filter(String query) {
        String needle = query.trim().toLowerCase(Locale.getDefault());
        visibleChats.clear();
        for (WhatsAppChats.Chat chat : allChats) {
            if (needle.isEmpty()
                    || chat.name.toLowerCase(Locale.getDefault()).contains(needle)
                    || chat.jid.contains(needle)) {
                visibleChats.add(chat);
            }
        }
        adapter.notifyDataSetChanged();
        emptyNotice.setVisibility(visibleChats.isEmpty() ? View.VISIBLE : View.GONE);
        emptyNotice.setText(allChats.isEmpty()
                ? "No chats to show. WhatsApp's contact list could not be read."
                : "Nothing matches that.");
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

    private final class ChatAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return visibleChats.size();
        }

        @Override
        public Object getItem(int position) {
            return visibleChats.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView == null ? newRow() : convertView;
            WhatsAppChats.Chat chat = visibleChats.get(position);

            TextView name = row.findViewById(NAME_ID);
            TextView detail = row.findViewById(DETAIL_ID);
            CheckBox box = row.findViewById(BOX_ID);

            name.setText(chat.name);
            detail.setText(chat.isGroup ? "Group" : chat.number());
            // Cleared first: the row is recycled, so a listener from the chat
            // that used to be here would fire for that chat, not this one.
            box.setOnCheckedChangeListener(null);
            box.setChecked(PatchDb.isChatSelected(ReadReceipts.FEATURE, chat.jid));
            box.setOnCheckedChangeListener((button, checked) ->
                    PatchDb.setChatSelected(ReadReceipts.FEATURE, chat.jid, checked));
            row.setOnClickListener(view -> box.toggle());
            return row;
        }

        private View newRow() {
            LinearLayout row = new LinearLayout(ChatPickerActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            row.setBackground(Palette.rowRipple(ChatPickerActivity.this));

            LinearLayout text = new LinearLayout(ChatPickerActivity.this);
            text.setOrientation(LinearLayout.VERTICAL);

            TextView name = new TextView(ChatPickerActivity.this);
            name.setId(NAME_ID);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            name.setTextColor(Palette.primaryText(ChatPickerActivity.this));
            text.addView(name);

            TextView detail = new TextView(ChatPickerActivity.this);
            detail.setId(DETAIL_ID);
            detail.setSingleLine(true);
            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            detail.setTextColor(Palette.secondaryText(ChatPickerActivity.this));
            text.addView(detail);

            row.addView(text, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            CheckBox box = new CheckBox(ChatPickerActivity.this);
            box.setId(BOX_ID);
            Palette.paintCheckable(box);
            row.addView(box);
            return row;
        }
    }

    // Ids for views built in code. Any non-zero constants work; these are
    // looked up only within a row this class built itself.
    private static final int NAME_ID = 0x7E000001;
    private static final int DETAIL_ID = 0x7E000002;
    private static final int BOX_ID = 0x7E000003;
}
