package com.smali_generator;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.smali_generator.utils.Utils;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tells the features that want it when an activity has resumed.
 *
 * This used to be a hook on {@link Activity#onResume()}, and under AOT that hook
 * never fired. The framework's onResume is 23 dex code units on API 36, inside
 * ART's 32-unit inline budget, so dex2oat copies its body into every app
 * {@code super.onResume()} call site it compiles -- WhatsApp's three base
 * activities, which every screen goes through. The rewritten entry point is
 * then never reached: `hooked=true` at load, and after
 * {@code cmd package compile -m speed}, or the background dexopt a daily
 * device runs on its own, no row and no log line. The framework's own lifecycle
 * callbacks are dispatched from inside that inlined body, as a real call into
 * {@link Application}, so they survive any compilation of the app and need no
 * hook at all.
 *
 * Still one owner with a list, rather than a registration per feature, so that
 * one listener throwing cannot cost another its row, and so that registering
 * stays lazy: {@link BootHealth}'s safe mode loads only the hooks whose
 * {@code toggleable()} is false, so the settings row can be the only
 * subscriber, and nothing here may assume the others ran.
 */
public final class ActivityResume {
    private static final String TAG = "PATCH";

    /** Notified once the activity's whole onResume has run, fragments included. */
    public interface Listener {
        void onActivityResumed(Activity activity);
    }

    /** Copy-on-write: read on every resume of every activity, written twice. */
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private static boolean installed;

    private ActivityResume() {
    }

    /**
     * Subscribes, registering with the Application the first time anyone does.
     *
     * Returns whether the dispatcher is registered once this call returns --
     * the post-call state, not just the outcome of this one attempt, so a
     * second subscriber arriving after an earlier one already succeeded gets
     * {@code true} without registering again. A caller that gets {@code false}
     * has still been added to {@link #listeners} -- a later successful call
     * registers for everyone already listed -- but nothing will call it until
     * then, so a caller logging its own "listening" line should gate it on this
     * return value rather than assume the call always worked.
     *
     * A failure is logged here and not thrown: a feature that never hears about
     * a resume loses its row, which is the same outcome as the screen having
     * changed shape, and neither is worth taking the host app down for.
     */
    public static synchronized boolean addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
        if (!installed) {
            try {
                // The provider runs after the Application object exists and
                // before its onCreate, so this is the instance every activity's
                // getApplication() will return.
                Application application = Utils.getApplication();
                if (application == null) {
                    Log.e(TAG, "ActivityResume: no Application yet, resumes will not be reported");
                } else {
                    application.registerActivityLifecycleCallbacks(new Dispatcher());
                    installed = true;
                    Log.i(TAG, "ActivityResume: registered for activity resumes");
                }
            } catch (Throwable t) {
                Log.e(TAG, "ActivityResume: could not register for activity resumes", t);
            }
        }
        return installed;
    }

    private static final class Dispatcher implements Application.ActivityLifecycleCallbacks {
        /**
         * Post-resumed rather than resumed: onActivityResumed is dispatched from
         * the first line of Activity.onResume, before the subclass has done any
         * of its own resuming, while this comes after all of it -- later than
         * the hook it replaces ever ran, which only helps a listener looking for
         * views the screen fills in.
         */
        @Override
        public void onActivityPostResumed(@NonNull Activity activity) {
            for (Listener listener : listeners) {
                // Per listener, so one feature throwing cannot cost another its
                // row -- or take the host app down from inside its resume.
                try {
                    listener.onActivityResumed(activity);
                } catch (Throwable t) {
                    Log.e(TAG, "ActivityResume: listener failed", t);
                }
            }
        }

        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
        }
    }
}
