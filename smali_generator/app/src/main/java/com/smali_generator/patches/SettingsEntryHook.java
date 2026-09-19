package com.smali_generator.patches;

import android.app.Activity;
import android.util.Log;
import android.view.View;

import com.smali_generator.ActivityResume;
import com.smali_generator.Hook;
import com.smali_generator.ui.SettingsEntryRow;

/**
 * Puts the patcher's own row into WhatsApp's settings list.
 *
 * The resume hook itself belongs to {@link ActivityResume}, which owns it for
 * every feature that wants one; the app-specific value here is only which
 * activity is the settings screen, and SettingsScreenFinder resolves that.
 *
 * Firing on every resume is deliberate. The row is added once per screen, and
 * a resume that arrives before the list has been populated simply finds
 * nothing to do; the next one fixes it.
 */
public class SettingsEntryHook implements Hook {
    private static final String TAG = "PATCH";

    private static final String SETTINGS_ACTIVITY = "{{SETTINGS_ACTIVITY_CLASS_NAME}}";

    private static void onResumed(Activity activity) {
        if (!SETTINGS_ACTIVITY.equals(activity.getClass().getName())) {
            return;
        }
        final View decor = activity.getWindow().getDecorView();
        // Posted rather than done here: the rows are inflated from view stubs,
        // and this runs before the layout pass that fills them in.
        decor.post(() -> SettingsEntryRow.injectInto(activity));
    }

    public String id() {
        // A database key. Renaming it silently resets the hook to its default.
        return "settings_entry";
    }

    public String title() {
        return "Settings shortcut";
    }

    public String description() {
        return "The row that opens this screen.";
    }

    public boolean toggleable() {
        return false;
    }

    public void load() {
        try {
            if (ActivityResume.addListener(SettingsEntryHook::onResumed)) {
                Log.i(TAG, "SettingsEntryHook: listening for resumes, settings screen is "
                        + SETTINGS_ACTIVITY);
            } else {
                // ActivityResume already logged why; this line is what makes the
                // absence show up under this hook's own name in `logcat -s PATCH`.
                Log.e(TAG, "SettingsEntryHook: ActivityResume did not install, "
                        + "the settings row will not appear");
            }
        } catch (Throwable t) {
            Log.e(TAG, "SettingsEntryHook: load failed", t);
        }
    }

    public void unload() {
        Log.i(TAG, "SettingsEntryHook: Patch unloaded");
    }
}
