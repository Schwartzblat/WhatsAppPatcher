package com.smali_generator.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Statistics");
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }
        gid = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_GID);
        setContentView(buildContent());
        content.addView(note("Group: " + gid));
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
        return scroller;
    }

    private TextView note(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Palette.secondaryText(this));
        view.setGravity(Gravity.START);
        return view;
    }
}
