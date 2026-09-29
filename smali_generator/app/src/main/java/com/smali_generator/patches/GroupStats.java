package com.smali_generator.patches;

import android.content.Intent;
import android.util.Log;

import com.smali_generator.Hook;
import com.smali_generator.HookCategory;
import com.smali_generator.ui.GroupInfoRows;
import com.smali_generator.ui.GroupStatsActivity;
import com.smali_generator.ui.Icons;
import com.smali_generator.ui.Palette;

/**
 * Adds a statistics row to WhatsApp's group info screen.
 *
 * Which activity that screen is, where on it a row goes and how the group's
 * jid is found all belong to {@link GroupInfoRows}, which several features
 * share; what is left here is the row itself and the hook's own identity.
 */
public class GroupStats implements Hook {
    private static final String TAG = "PATCH";

    private static final String ROW_TAG = GroupInfoRows.Tags.PREFIX + "statistics";

    public String id() {
        // A database key. Renaming it silently resets the hook to its default.
        return "group_stats";
    }

    public String title() {
        return "Group statistics";
    }

    public String description() {
        return "Who talks most in a group, and when.";
    }

    public HookCategory category() {
        return HookCategory.INTERFACE;
    }

    public void load() {
        try {
            GroupInfoRows.Row row = new GroupInfoRows.Row(ROW_TAG, "Statistics",
                    "Who talks most, and when",
                    activity -> Icons.statistics(activity, Palette.ACCENT),
                    (activity, gid) -> {
                        Intent intent = new Intent(activity, GroupStatsActivity.class);
                        intent.putExtra(GroupStatsActivity.EXTRA_GID, gid);
                        activity.startActivity(intent);
                    });
            if (GroupInfoRows.register(row)) {
                Log.i(TAG, "GroupStats: the statistics row is registered");
            } else {
                // GroupInfoRows already logged why; this line is what makes the
                // absence show up under this hook's own name in `logcat -s PATCH`.
                Log.e(TAG, "GroupStats: resumes are not being heard, "
                        + "the statistics row will not appear");
            }
        } catch (Throwable t) {
            Log.e(TAG, "GroupStats: load failed", t);
        }
    }

    public void unload() {
        Log.i(TAG, "GroupStats: Patch unloaded");
    }
}
