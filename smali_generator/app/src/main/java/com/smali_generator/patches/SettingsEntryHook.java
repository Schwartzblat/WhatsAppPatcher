package com.smali_generator.patches;

import android.app.Activity;
import android.util.Log;
import android.view.View;

import com.arthooks.ArtHooks;
import com.smali_generator.Hook;
import com.smali_generator.ui.SettingsEntryRow;

import java.lang.reflect.Method;

/**
 * Puts the patcher's own row into WhatsApp's settings list.
 *
 * The hook is on {@link Activity#onResume()} -- a framework method, so nothing
 * about the target of the hook can be renamed by an obfuscator, and every
 * activity reaches it because the framework requires the super call. The one
 * app-specific value is which activity is the settings screen, and
 * SettingsScreenFinder resolves that.
 *
 * Firing on every resume is deliberate. The row is added once per screen, and
 * a resume that arrives before the list has been populated simply finds
 * nothing to do; the next one fixes it.
 */
public class SettingsEntryHook implements Hook {
    private static final String TAG = "PATCH";

    private static final String SETTINGS_ACTIVITY = "{{SETTINGS_ACTIVITY_CLASS_NAME}}";

    /** native on purpose: a body here would be inlined into the hook and the backup would
     * silently answer for the original. ArtHooks rewrites its entry point. */
    static native void on_resume_backup(Object thiz);

    static void on_resume_hook(Object thiz) {
        // First, and outside the try: the framework checks that
        // Activity.onResume ran, and takes the app down if it did not.
        on_resume_backup(thiz);
        try {
            if (!SETTINGS_ACTIVITY.equals(thiz.getClass().getName())) {
                return;
            }
            final Activity activity = (Activity) thiz;
            final View decor = activity.getWindow().getDecorView();
            // Posted rather than done here: the rows are inflated from view
            // stubs, and this runs before the layout pass that fills them in.
            decor.post(() -> SettingsEntryRow.injectInto(activity));
        } catch (Throwable t) {
            Log.e(TAG, "SettingsEntryHook: resume handling failed", t);
        }
    }

    public String id() {
        return "settings_entry";
    }

    public String title() {
        return "Settings shortcut";
    }

    public String description() {
        return "Adds the Patcher row to WhatsApp's own settings.";
    }

    public boolean toggleable() {
        return false;
    }

    public void load() {
        try {
            Method original = Activity.class.getDeclaredMethod("onResume");
            Method replacement = SettingsEntryHook.class.getDeclaredMethod(
                    "on_resume_hook", Object.class);
            Method backup = SettingsEntryHook.class.getDeclaredMethod(
                    "on_resume_backup", Object.class);
            if (!ArtHooks.hook_function(original, replacement, backup)) {
                Log.e(TAG, "SettingsEntryHook: hook_function refused Activity.onResume");
                return;
            }
            Log.i(TAG, "SettingsEntryHook: hooked Activity.onResume, settings screen is "
                    + SETTINGS_ACTIVITY);
        } catch (Throwable t) {
            Log.e(TAG, "SettingsEntryHook: load failed", t);
        }
    }

    public void unload() {
        Log.i(TAG, "SettingsEntryHook: Patch unloaded");
    }
}
