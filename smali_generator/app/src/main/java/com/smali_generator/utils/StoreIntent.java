package com.smali_generator.utils;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

/**
 * Opens the androidmod store's update flow for a package.
 *
 * The store builds and signs patched builds on the phone itself, so it is the
 * only thing that can update one: the official APK and the Play Store both
 * carry a different signature, and Android refuses the install. The contract is
 * the store's -- an explicit action, the store's own package, the target package
 * as a string extra -- and the receiving side lives in the store app.
 */
public final class StoreIntent {

    public static final String ACTION = "site.androidmod.store.action.UPDATE_APP";
    public static final String STORE_PACKAGE = "site.androidmod.store";
    public static final String EXTRA_PACKAGE = "package";

    static final String NO_STORE = "Update WhatsApp from the androidmod store app";

    private StoreIntent() {}

    /**
     * Starts the store's update flow for {@code packageName}.
     *
     * Returns false when the store is not installed, after telling the user
     * where updates come from -- the caller then decides whether to fall back.
     */
    public static boolean open(Context context, String packageName) {
        // NEW_TASK because a caller may hold an application context, and the
        // store's flow is a task of its own rather than a screen of WhatsApp's.
        Intent intent = new Intent(ACTION)
                .setPackage(STORE_PACKAGE)
                .putExtra(EXTRA_PACKAGE, packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            Log.i("PATCH", "StoreIntent: opened the store's update flow for " + packageName);
            return true;
        } catch (ActivityNotFoundException e) {
            Log.w("PATCH", "StoreIntent: the store app is not installed");
            toast(context);
            return false;
        }
    }

    private static void toast(Context context) {
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        // Posted rather than shown: a Toast needs a Looper thread, and a start
        // can come from any thread.
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app, NO_STORE, Toast.LENGTH_LONG).show());
    }
}
