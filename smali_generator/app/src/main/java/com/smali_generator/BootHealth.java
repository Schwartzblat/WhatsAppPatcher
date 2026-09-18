package com.smali_generator;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.util.Log;

import com.smali_generator.abprops.Hunt;
import com.smali_generator.db.PatchDb;
import com.smali_generator.utils.Utils;

import java.util.List;

/**
 * Notices when the patched app stops starting, and takes the patch back out of
 * the way.
 *
 * The thing that makes an A/B property override dangerous is not that it can
 * break the app -- it is that the only screen that can undo one lives inside
 * the app it broke. A property that stops WhatsApp reaching its home screen
 * cannot be un-set by hand: the way in is gone, and what is left is clearing
 * the app's data. This is the way back.
 *
 * How a previous run ended is not guessed at. Android keeps the reasons its
 * processes died and hands them to the app they belonged to, so this asks:
 * anything that ended in a crash, a native crash or an ANR counts against the
 * patch, and a process that was stopped, swapped out or simply not needed any
 * more does not. A timer was the obvious alternative and is wrong -- the first
 * thing it did on a real device was hold every override back because installing
 * the APK had started the process for a broadcast and let it go a few seconds
 * later, which is not a failure at all.
 *
 * Two rungs, and the second one is sticky:
 *
 * <ol>
 * <li>one bad exit -- every override is held back, and the app answers with its
 *     own values again. Held back, not deleted: the run that crashed may have
 *     had nothing to do with them, and a list of overrides thrown away over an
 *     unrelated crash would be a worse failure than the one being prevented.
 *     One tap puts them back.</li>
 * <li>two in a row, with the overrides already out of the way -- safe mode,
 *     which loads only the hooks {@link Hook#toggleable()} says the patched app
 *     cannot run without. Those four are the signature and file-integrity
 *     bypasses, the Firebase key, and the settings row -- so the app still
 *     starts and there is still a way in to see why. Sticky until it is turned
 *     off by hand, because an intermittent crash must not quietly re-arm
 *     whatever caused it.</li>
 * </ol>
 */
public final class BootHealth {

    private static final String TAG = "PATCH";

    /** Bad exits seen in a row. Reset by the first launch that finds none new. */
    private static final String STRIKES_KEY = "boot_strikes";

    /** The newest exit already counted, so one death is never counted twice. */
    private static final String LAST_EXIT_KEY = "boot_last_exit_at";

    private static final String SAFE_MODE_KEY = "boot_safe_mode";

    /** How many exits back to look. More than enough between two launches. */
    private static final int HISTORY = 16;

    /** How many bad exits in a row before the patch stands aside entirely. */
    private static final int SAFE_MODE_AFTER = 2;

    /**
     * Whether the run before this one ended badly, for anything that wants the
     * same answer this does. Worked out once, in {@link #begin}.
     */
    private static volatile boolean lastRunCrashed;

    /** What this launch should do about it. */
    public enum Mode {
        /** Nothing is wrong. */
        NORMAL,
        /** One bad exit: the overrides do not go in this time. */
        HOLD_BACK_OVERRIDES,
        /** Two: only the hooks the patched app cannot run without. */
        SAFE
    }

    private BootHealth() {
    }

    /**
     * Reads how the last runs ended and says what this one should load.
     *
     * Total, like everything else that runs before the app: on any failure it
     * reports {@link Mode#NORMAL}, which is the behaviour of a build without
     * this class at all. Being unable to tell whether the app crashed is not a
     * reason to start taking the patch apart.
     */
    public static Mode begin() {
        try {
            if (PatchDb.getFlag(SAFE_MODE_KEY, false)) {
                Log.e(TAG, "BootHealth: safe mode -- only the required hooks will load."
                        + " Turn it off on the patcher's settings screen.");
                return Mode.SAFE;
            }

            int badExits = countBadExits();
            lastRunCrashed = badExits > 0;

            // A hunt crashes the app on purpose, so the ladder would climb
            // itself into safe mode within two trials and take the hunt's own
            // hook down with it. The hunt judges its own crashes; this only
            // reports them.
            if (Hunt.isActive()) {
                PatchDb.setInt(STRIKES_KEY, 0);
                return Mode.NORMAL;
            }

            int strikes = badExits == 0 ? 0 : PatchDb.getInt(STRIKES_KEY, 0) + badExits;
            PatchDb.setInt(STRIKES_KEY, strikes);

            if (strikes >= SAFE_MODE_AFTER) {
                // Holding the overrides back did not stop it, so the patch
                // itself is the suspect. Sticky from here.
                PatchDb.setFlag(SAFE_MODE_KEY, true);
                Log.e(TAG, "BootHealth: " + strikes + " bad exits in a row with the overrides"
                        + " already held back; entering safe mode");
                return Mode.SAFE;
            }
            if (strikes >= 1) {
                Log.e(TAG, "BootHealth: the last run ended badly; holding every A/B property"
                        + " override back for this one");
                return Mode.HOLD_BACK_OVERRIDES;
            }
            return Mode.NORMAL;
        } catch (Throwable t) {
            Log.e(TAG, "BootHealth: could not read the boot state, carrying on as normal", t);
            return Mode.NORMAL;
        }
    }

    /**
     * How many times this process has died badly since the last time anyone
     * looked.
     *
     * Only this process: WhatsApp runs several, and one of the others falling
     * over says nothing about whether the patch can start. Only exits newer
     * than the last one counted, so a single crash is not held against every
     * launch that follows it.
     */
    private static int countBadExits() {
        Context context = Utils.getApplicationContext();
        if (context == null) {
            return 0;
        }
        ActivityManager activities = context.getSystemService(ActivityManager.class);
        if (activities == null) {
            return 0;
        }
        List<ApplicationExitInfo> history =
                activities.getHistoricalProcessExitReasons(context.getPackageName(), 0, HISTORY);
        if (history == null || history.isEmpty()) {
            return 0;
        }
        String self = processName(context);
        long lastCounted = readLong(LAST_EXIT_KEY);
        long newest = lastCounted;
        int bad = 0;
        for (ApplicationExitInfo exit : history) {
            long at = exit.getTimestamp();
            if (at <= lastCounted) {
                continue;
            }
            newest = Math.max(newest, at);
            if (self != null && !self.equals(exit.getProcessName())) {
                continue;
            }
            if (isBad(exit.getReason())) {
                bad++;
                Log.e(TAG, "BootHealth: a previous run ended in " + describe(exit.getReason())
                        + " -- " + exit.getDescription());
            }
        }
        if (newest != lastCounted) {
            writeLong(LAST_EXIT_KEY, newest);
        }
        return bad;
    }

    /**
     * A death the patch could plausibly have caused.
     *
     * Deliberately narrow. Being stopped, swapped out for memory, or killed by
     * the user are all ordinary ends to a process, and counting them would hold
     * overrides back on a device that had done nothing wrong.
     */
    private static boolean isBad(int reason) {
        return reason == ApplicationExitInfo.REASON_CRASH
                || reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                || reason == ApplicationExitInfo.REASON_ANR
                || reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE;
    }

    private static String describe(int reason) {
        if (reason == ApplicationExitInfo.REASON_CRASH) {
            return "a crash";
        }
        if (reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
            return "a native crash";
        }
        if (reason == ApplicationExitInfo.REASON_ANR) {
            return "an ANR";
        }
        return "a failure to start";
    }

    private static String processName(Context context) {
        try {
            return android.app.Application.getProcessName();
        } catch (Throwable t) {
            // Without it every process's exits are counted, which is noisier
            // but still only counts crashes. Better than counting none.
            Log.e(TAG, "BootHealth: could not read this process's name", t);
            return null;
        }
    }

    /** Timestamps do not fit an int, and the settings table stores text. */
    private static long readLong(String key) {
        try {
            return Long.parseLong(PatchDb.getString(key, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void writeLong(String key, long value) {
        PatchDb.setString(key, Long.toString(value));
    }

    /** Set by {@link #begin}, so callers see the same verdict it acted on. */
    public static boolean lastRunCrashed() {
        return lastRunCrashed;
    }

    public static boolean inSafeMode() {
        return PatchDb.getFlag(SAFE_MODE_KEY, false);
    }

    /**
     * Leaves safe mode, and forgets the bad exits that caused it.
     *
     * The strikes go too: leaving them where they were would put the very next
     * launch back on the last rung of the ladder, which is not what "turn the
     * rest back on" means.
     */
    public static void leaveSafeMode() {
        PatchDb.setFlag(SAFE_MODE_KEY, false);
        PatchDb.setInt(STRIKES_KEY, 0);
        Log.i(TAG, "BootHealth: safe mode off");
    }
}
