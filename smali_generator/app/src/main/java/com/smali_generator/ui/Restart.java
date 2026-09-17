package com.smali_generator.ui;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Process;
import android.util.Log;

/**
 * Restarting the host app, which is what applies a change ArtHooks cannot undo.
 *
 * Shared because two screens need it: a hook is installed or absent for the
 * life of a process, and a property the app has already read is cached in
 * fields all over it.
 */
public final class Restart {

    private static final String TAG = "PATCH";

    /**
     * Long enough for this process to be gone before the alarm fires, short
     * enough that the app is back before the launcher finishes animating.
     */
    private static final long DELAY_MS = 300;

    private Restart() {
    }

    /**
     * Kills the process, having first asked the system to launch the app again
     * shortly afterwards.
     *
     * The alarm is best effort: if scheduling it fails the process still dies,
     * which is the part that actually applies the change, and the user reopens
     * the app themselves.
     */
    public static void now(Activity activity) {
        try {
            Intent launch = activity.getPackageManager()
                    .getLaunchIntentForPackage(activity.getPackageName());
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                PendingIntent pending = PendingIntent.getActivity(activity, 0, launch,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
                AlarmManager alarms = activity.getSystemService(AlarmManager.class);
                if (alarms != null) {
                    alarms.set(AlarmManager.RTC, System.currentTimeMillis() + DELAY_MS, pending);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Restart: could not schedule the relaunch", t);
        }
        activity.finishAffinity();
        Process.killProcess(Process.myPid());
    }

    /** The green pill both screens restart from. */
    public static android.widget.Button button(Activity activity, String label) {
        android.widget.Button button = new android.widget.Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setLetterSpacing(0.01f);
        button.setTextColor(Palette.ON_ACCENT);
        // The framework button draws its own grey nine-patch and lifts on
        // press; both have to go before a flat pill looks like anything.
        button.setStateListAnimator(null);
        button.setBackground(Palette.pill(activity));
        int side = Palette.dp(activity, 24);
        int top = Palette.dp(activity, 14);
        button.setPadding(side, top, side, top);
        button.setMinimumHeight(Palette.dp(activity, 52));
        button.setOnClickListener(view -> now(activity));
        return button;
    }
}
