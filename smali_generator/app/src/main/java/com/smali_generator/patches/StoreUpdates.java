package com.smali_generator.patches;

import android.util.Log;

import com.arthooks.ArtHooks;

import com.smali_generator.Artifacts;
import com.smali_generator.Hook;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/*
 * WhatsApp's self-updater, as located on 2.26.40.72 (website build):
 *
 *   X.Fuz -- the startup task whose tag is "WebsiteDistributionCheck"
 *     (implements X.0Aj, the startup-task interface, 184 implementers).
 *     Bf0()V, 208 code units, runs on every launch:
 *       - sets X.Hkv.A0D/A0E/A0F to .../WhatsApp.apk, .sha256, .version
 *         ("upgrade source initialized; path=");
 *       - if Settings.Secure "install_non_market_apps" == 1, the network check
 *         passes and last_upgrade_check is six hours old, posts X.INy case 24
 *         -- the only place that job is created.
 *   X.INy case 24 -- "upgrade check started": compares versions, downloads the
 *     APK into WhatsApp.download, checks its sha256, renames it to WhatsApp.apk
 *     and has X.Hkv.A04() write the WhatsApp.upgrade sentinel.
 *   com.whatsapp.Main -- on the next launch IO8 case 15 finds the sentinel and
 *     the APK, and Main.onCreateDialog(0) ("main/dialog/upgrade") offers to
 *     install it; its positive button starts an
 *     application/vnd.android.package-archive install over this build, which
 *     fails on the signature.
 *
 * Not the helper X.Hkv, though it holds the APK URL: A03()Uri (the URL, 21
 * code units) and A04()V (the sentinel and "upgrade/sentinel/fail", 25) are
 * each under ART's 32-unit inline budget, and neither starts anything. On
 * 2.26.39.79 those two strings are in different classes, and that build -- like
 * 2.26.36.74 -- has no self-updater at all: its WebsiteDistributionCheck task
 * is a bare return-void, so the finder declines and this hook is not loaded.
 *
 * The update *links* (expiry dialogs, banners, the market:// and APK URL
 * buttons) are StoreUpdateLinks's; they keep working, and keep going to the
 * store, with this task silenced, since X.Hkv.A03 falls back to the fixed URL.
 */

/**
 * Stops WhatsApp downloading its own updates.
 *
 * The download can never be installed over this build, so it is 150-odd MB of
 * nothing every release, followed by an install prompt that fails. Silencing
 * the startup task that schedules it takes away both: no download, no
 * sentinel, and the prompt has nothing to offer.
 *
 * It deliberately does not open the store. The task runs on every launch, so
 * doing anything visible from here would put the store in front of WhatsApp
 * each time it starts; the store has its own update check, and WhatsApp's own
 * update links reach it through StoreUpdateLinks.
 */
public class StoreUpdates implements Hook {

    private static final String TAG = "PATCH";

    /** Volatile so "once" holds whichever thread the startup tasks run on. */
    private static volatile boolean logged;

    /** native on purpose: a body here would be inlined into the hook and the backup would
     * silently answer for the original. ArtHooks rewrites its entry point. */
    static native void check_backup(Object thiz);

    static void check_hook(Object thiz) {
        if (!logged) {
            logged = true;
            Log.i(TAG, "StoreUpdates: WhatsApp's own update check skipped, nothing downloaded");
        }
    }

    public String id() {
        return "store_updates";
    }

    public String title() {
        return "Updates through the store";
    }

    public String description() {
        return "WhatsApp stops downloading updates it cannot install; the store app updates it.";
    }

    public void load() {
        try {
            Class<?> task = Class.forName(Artifacts.get("UPGRADE_CHECK_CLASS_NAME"));
            Executable check = ArtHooks.find_function(task,
                    Artifacts.get("UPGRADE_CHECK_METHOD_NAME"), Artifacts.get("UPGRADE_CHECK_METHOD_SIG"));
            // R8 can staticize a method without changing the descriptor the
            // finder matched, and a static target re-enters its replacement.
            if (Modifier.isStatic(check.getModifiers())) {
                Log.e(TAG, "StoreUpdates: " + task.getName() + "." + check.getName()
                        + " is static, not hooked; WhatsApp keeps its own updater");
                return;
            }
            Method replacement = StoreUpdates.class.getDeclaredMethod("check_hook", Object.class);
            Method original = StoreUpdates.class.getDeclaredMethod("check_backup", Object.class);
            boolean hooked = ArtHooks.hook_function(check, replacement, original);

            Log.i(TAG, "StoreUpdates: update check hooked on " + task.getName()
                    + "." + Artifacts.get("UPGRADE_CHECK_METHOD_NAME") + ", hooked=" + hooked);
        } catch (Throwable t) {
            Log.e(TAG, "StoreUpdates: WhatsApp keeps its own updater: " + t);
        }
    }

    public void unload() {
        Log.i(TAG, "StoreUpdates: unloaded");
    }
}
