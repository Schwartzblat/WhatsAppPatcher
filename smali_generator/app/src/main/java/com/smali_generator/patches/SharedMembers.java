package com.smali_generator.patches;

import android.content.Intent;
import android.util.Log;

import com.smali_generator.Hook;
import com.smali_generator.HookCategory;
import com.smali_generator.ui.GroupInfoRows;
import com.smali_generator.ui.Icons;
import com.smali_generator.ui.Palette;
import com.smali_generator.ui.SharedMembersActivity;

/**
 * Adds a "Shared members" row to WhatsApp's group info screen.
 *
 * The screen it opens crosses a group's membership with another group's and
 * shows who is in both -- see {@link SharedMembersActivity}. Nothing is
 * hooked: the membership is WhatsApp's own {@code group_participant_user},
 * read from inside its process, so the only app-specific thing this needs is
 * which activity the group info screen is, which {@link GroupInfoRows}
 * already knows.
 *
 * It also offers the screen from the patcher's own settings, where it opens
 * with nothing picked -- crossing two groups is a question worth asking
 * without having navigated into one of them first.
 */
public class SharedMembers implements Hook {
    private static final String TAG = "PATCH";

    private static final String ROW_TAG = GroupInfoRows.Tags.PREFIX + "shared_members";

    public String id() {
        // A database key. Renaming it silently resets the hook to its default.
        return "shared_members";
    }

    public String title() {
        return "Shared members";
    }

    public String description() {
        return "Who a group has in common with your other groups.";
    }

    public HookCategory category() {
        return HookCategory.INTERFACE;
    }

    public String configSummary() {
        return "Cross any two groups";
    }

    public Class<?> configScreen() {
        return SharedMembersActivity.class;
    }

    public void load() {
        try {
            GroupInfoRows.Row row = new GroupInfoRows.Row(ROW_TAG, "Shared members",
                    "Who is also in your other groups",
                    activity -> Icons.sharedMembers(activity, Palette.ACCENT),
                    (activity, gid) -> {
                        Intent intent = new Intent(activity, SharedMembersActivity.class);
                        intent.putExtra(SharedMembersActivity.EXTRA_GID, gid);
                        activity.startActivity(intent);
                    });
            if (GroupInfoRows.register(row)) {
                Log.i(TAG, "SharedMembers: the shared members row is registered");
            } else {
                // GroupInfoRows already logged why; this line is what makes the
                // absence show up under this hook's own name in `logcat -s PATCH`.
                Log.e(TAG, "SharedMembers: resumes are not being heard, "
                        + "the shared members row will not appear");
            }
        } catch (Throwable t) {
            Log.e(TAG, "SharedMembers: load failed", t);
        }
    }

    public void unload() {
        Log.i(TAG, "SharedMembers: Patch unloaded");
    }
}
