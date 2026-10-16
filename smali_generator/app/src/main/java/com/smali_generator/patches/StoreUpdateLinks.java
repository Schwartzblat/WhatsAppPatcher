package com.smali_generator.patches;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import com.arthooks.ArtHooks;

import com.smali_generator.Hook;
import com.smali_generator.utils.StoreIntent;
import com.smali_generator.utils.UpdateLinks;
import com.smali_generator.utils.Utils;

import java.lang.reflect.Method;

/**
 * Sends WhatsApp's "update WhatsApp" links to the store.
 *
 * The expiry dialogs, the update banners and the "needs a newer version"
 * prompts all end in an ACTION_VIEW of the website's APK or of the Play
 * listing -- about seventeen call sites on 2.26.40.72, each behind obfuscated
 * names that move every release, and two of them are dialogs whose builders
 * are under the inline budget. Both destinations install a build signed with
 * another key, which Android refuses. So the redirect sits on the one place
 * every one of those starts passes through: the framework's
 * {@code Instrumentation.execStartActivity}, which {@code Activity.startActivity}
 * and {@code ContextImpl.startActivity} both call, and which is far too large to
 * inline. No finder, no keys: it keys on the link.
 *
 * WhatsApp's intent launcher may hand the framework a chooser rather than the
 * link itself, when more than one app can open it; the link is then the
 * chooser's EXTRA_INTENT, and that is what is checked.
 *
 * A link is swallowed only when the store actually opened. Without the store
 * the original start goes ahead, so the user still gets the browser or Play
 * listing rather than a dead button.
 */
public class StoreUpdateLinks implements Hook {

    private static final String TAG = "PATCH";
    private static final String WHATSAPP = "com.whatsapp";

    /** native on purpose: a body here would be inlined into the hook and the backup would
     * silently answer for the original. ArtHooks rewrites its entry point. */
    static native Instrumentation.ActivityResult start_backup(Object thiz, Context who, IBinder contextThread,
            IBinder token, Activity target, Intent intent, int requestCode, Bundle options);

    /** Activity.startActivityForResult, and ContextImpl.startActivity with a null Activity. */
    static Instrumentation.ActivityResult start_hook(Object thiz, Context who, IBinder contextThread,
            IBinder token, Activity target, Intent intent, int requestCode, Bundle options) {
        if (sentToStore(who, intent)) {
            // What the framework returns for any start that has no immediate result.
            return null;
        }
        return start_backup(thiz, who, contextThread, token, target, intent, requestCode, options);
    }

    /** native on purpose, as above. */
    static native Instrumentation.ActivityResult start_named_backup(Object thiz, Context who, IBinder contextThread,
            IBinder token, String target, Intent intent, int requestCode, Bundle options);

    /** The same start addressed by a fragment's or child's id rather than an Activity. */
    static Instrumentation.ActivityResult start_named_hook(Object thiz, Context who, IBinder contextThread,
            IBinder token, String target, Intent intent, int requestCode, Bundle options) {
        if (sentToStore(who, intent)) {
            return null;
        }
        return start_named_backup(thiz, who, contextThread, token, target, intent, requestCode, options);
    }

    /**
     * Whether this start was a WhatsApp update link and the store took it instead.
     *
     * Every activity start in the process comes through here, so it must not
     * throw and must be cheap for the starts it leaves alone.
     */
    private static boolean sentToStore(Context who, Intent intent) {
        try {
            Intent link = linkOf(intent);
            if (link == null) {
                return false;
            }
            ComponentName component = link.getComponent();
            if (!UpdateLinks.isWhatsAppUpdate(link.getAction(), link.getDataString(), link.getPackage(),
                    component == null ? null : component.getPackageName())) {
                return false;
            }
            Context context = who != null ? who : Utils.getApplicationContext();
            // Re-enters this hook with the store's own intent, which is not a
            // VIEW and is addressed to the store, so it goes straight through.
            boolean opened = StoreIntent.open(context, WHATSAPP);
            Log.i(TAG, "StoreUpdateLinks: " + link.getDataString()
                    + (opened ? " sent to the store" : " left to WhatsApp, the store is not installed"));
            return opened;
        } catch (Throwable t) {
            Log.e(TAG, "StoreUpdateLinks: left a start alone: " + t);
            return false;
        }
    }

    /** The intent that names the destination: the start itself, or the one a chooser wraps. */
    @SuppressWarnings("deprecation")
    private static Intent linkOf(Intent intent) {
        if (intent != null && Intent.ACTION_CHOOSER.equals(intent.getAction())) {
            // The untyped getter: the typed one is broken on API 33, which is
            // why AndroidX's IntentCompat only uses it from 34 on.
            Object inner = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            return inner instanceof Intent ? (Intent) inner : null;
        }
        return intent;
    }

    public String id() {
        return "store_update_links";
    }

    public String title() {
        return "Update links open the store";
    }

    public String description() {
        return "WhatsApp's update buttons open the store app instead of Play or the website.";
    }

    public void load() {
        hook("Activity", Activity.class, "start_hook", "start_backup");
        hook("named", String.class, "start_named_hook", "start_named_backup");
    }

    /**
     * Installs one overload. They differ only in how the starting screen is
     * named, and each is installed on its own so a framework without one still
     * gets the other.
     */
    private static void hook(String which, Class<?> target, String replacementName, String backupName) {
        try {
            Class<?>[] params = {Context.class, IBinder.class, IBinder.class, target, Intent.class,
                    int.class, Bundle.class};
            Method start = Instrumentation.class.getDeclaredMethod("execStartActivity", params);
            Class<?>[] hookParams = new Class<?>[params.length + 1];
            hookParams[0] = Object.class;
            System.arraycopy(params, 0, hookParams, 1, params.length);
            Method replacement = StoreUpdateLinks.class.getDeclaredMethod(replacementName, hookParams);
            Method original = StoreUpdateLinks.class.getDeclaredMethod(backupName, hookParams);
            boolean hooked = ArtHooks.hook_function(start, replacement, original);
            Log.i(TAG, "StoreUpdateLinks: execStartActivity (" + which + ") hooked=" + hooked);
        } catch (Throwable t) {
            Log.e(TAG, "StoreUpdateLinks: execStartActivity (" + which + ") left alone: " + t);
        }
    }

    public void unload() {
        Log.i(TAG, "StoreUpdateLinks: unloaded");
    }
}
