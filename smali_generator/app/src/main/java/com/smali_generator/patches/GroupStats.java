package com.smali_generator.patches;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.smali_generator.ActivityResume;
import com.smali_generator.Hook;
import com.smali_generator.HookCategory;
import com.smali_generator.ui.GroupStatsActivity;
import com.smali_generator.ui.GroupStatsRow;

import java.util.regex.Pattern;

/**
 * Adds a statistics row to WhatsApp's group info screen.
 *
 * The resume hook belongs to {@link ActivityResume}; the app-specific value
 * here is which activity is the group info screen, and GroupInfoFinder
 * resolves that.
 *
 * The group's jid is read off the Intent rather than resolved by a finder.
 * WhatsApp passes it as a plain String through the framework's own
 * getStringExtra, so there is no obfuscated type in the way and none of the
 * Jid.toString() trap -- that method returns the form WhatsApp prints in its
 * logs, not the addressable one.
 */
public class GroupStats implements Hook {
    private static final String TAG = "PATCH";

    private static final String GROUP_INFO_ACTIVITY = "{{GROUP_INFO_ACTIVITY_CLASS_NAME}}";

    /** The key WhatsApp has used for it; the shape check is the fallback. */
    private static final String GID_EXTRA = "gid";

    /** A group jid: plain, or the "created by"-suffixed form older groups use. */
    private static final Pattern GROUP_JID = Pattern.compile("^\\d+(-\\d+)?@g\\.us$");

    private static void onResumed(Activity activity) {
        if (!GROUP_INFO_ACTIVITY.equals(activity.getClass().getName())) {
            return;
        }
        final String gid = gidOf(activity);
        if (gid == null) {
            Log.e(TAG, "GroupStats: no group jid on the group info intent, no row added");
            return;
        }
        GroupStatsRow.injectWhenReady(activity, gid);
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
                Log.e(TAG, "GroupStats: two different group jids on the intent, declining");
                return null;
            }
            found = (String) value;
        }
        return found;
    }

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
            if (ActivityResume.addListener(GroupStats::onResumed)) {
                Log.i(TAG, "GroupStats: listening for resumes, group info screen is "
                        + GROUP_INFO_ACTIVITY);
            } else {
                // ActivityResume already logged why; this line is what makes the
                // absence show up under this hook's own name in `logcat -s PATCH`.
                Log.e(TAG, "GroupStats: ActivityResume did not install, "
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
