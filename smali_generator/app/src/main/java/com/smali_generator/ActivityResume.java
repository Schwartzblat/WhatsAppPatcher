package com.smali_generator;

import android.app.Activity;
import android.util.Log;

import com.arthooks.ArtHooks;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one owner of the hook on {@link Activity#onResume()}.
 *
 * ArtHooks installs a redirect per method, and a second hook on a method
 * already hooked does not chain -- it re-enters. Two features now want to know
 * when an activity resumes (the settings row and the group statistics row), so
 * the hook lives here and they subscribe.
 *
 * Installation is lazy rather than done at startup, and that is load-bearing:
 * {@link BootHealth}'s safe mode loads only the hooks whose
 * {@code toggleable()} is false, so the settings row can be the only
 * subscriber, and the dispatcher has to work with one listener and no
 * assumption that the others ran.
 *
 * Hooking a framework method is deliberate -- nothing about it can be renamed
 * by an obfuscator, and every activity reaches it because the framework
 * requires the super call.
 */
public final class ActivityResume {
    private static final String TAG = "PATCH";

    /** Notified after the original onResume has run. */
    public interface Listener {
        void onActivityResumed(Activity activity);
    }

    /** Copy-on-write: read on every resume of every activity, written twice. */
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private static boolean installed;

    private ActivityResume() {
    }

    /**
     * Subscribes, installing the hook the first time anyone does.
     *
     * A failure to install is logged and swallowed: a feature that never hears
     * about a resume loses its row, which is the same outcome as the screen
     * having changed shape, and neither is worth taking the host app down for.
     */
    public static synchronized void addListener(Listener listener) {
        if (listener == null) {
            return;
        }
        listeners.add(listener);
        if (installed) {
            return;
        }
        try {
            Method original = Activity.class.getDeclaredMethod("onResume");
            Method replacement = ActivityResume.class.getDeclaredMethod(
                    "on_resume_hook", Object.class);
            Method backup = ActivityResume.class.getDeclaredMethod(
                    "on_resume_backup", Object.class);
            if (!ArtHooks.hook_function(original, replacement, backup)) {
                Log.e(TAG, "ActivityResume: hook_function refused Activity.onResume");
                return;
            }
            installed = true;
            Log.i(TAG, "ActivityResume: hooked Activity.onResume");
        } catch (Throwable t) {
            Log.e(TAG, "ActivityResume: could not hook Activity.onResume", t);
        }
    }

    /**
     * native on purpose: a body here would be inlined into the hook by dex2oat
     * and the backup would silently answer for the original. ArtHooks rewrites
     * its entry point instead.
     */
    static native void on_resume_backup(Object thiz);

    static void on_resume_hook(Object thiz) {
        // First, and outside the try: the framework checks that
        // Activity.onResume ran, and takes the app down if it did not.
        on_resume_backup(thiz);
        if (!(thiz instanceof Activity)) {
            return;
        }
        Activity activity = (Activity) thiz;
        for (Listener listener : listeners) {
            // Per listener, so one feature throwing cannot cost another its
            // row -- or take the host app down from inside its own onResume.
            try {
                listener.onActivityResumed(activity);
            } catch (Throwable t) {
                Log.e(TAG, "ActivityResume: listener failed", t);
            }
        }
    }
}
