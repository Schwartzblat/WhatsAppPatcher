package com.smali_generator.patches;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import com.smali_generator.Artifacts;
import com.smali_generator.Hook;
import com.smali_generator.HookCategory;
import com.smali_generator.utils.AppCert;
import com.smali_generator.utils.MapsTokenService;
import com.smali_generator.utils.Utils;

import java.util.concurrent.Executor;

/**
 * Answers the Google Maps SDK's bind to Play services' ApiTokenService with a
 * local binder that fetches the token using WhatsApp's original certificate.
 *
 * Play services reads the caller's certificate from the system, so
 * PackageManagerHook's in-process spoof never reaches it: without this, every
 * map in a re-signed WhatsApp stays blank with "Authorization failure".
 */
public class MapsTokenHook implements Hook {

    private static final String TAG = "PATCH";
    private static final String GMS = "com.google.android.gms";
    private static final String TOKEN_SERVICE = "com.google.android.gms.maps.auth.ApiTokenService";
    private static final String[] MAPS_KEY_META_DATA = {
            "com.google.android.geo.API_KEY", "com.google.android.maps.v2.API_KEY",
    };

    private static final BindServiceHook.Handler HANDLER = new BindServiceHook.Handler() {
        @Override
        public BindServiceHook.Decision onBind(Context thiz, Intent intent, ServiceConnection conn,
                                               Executor executor) {
            ComponentName component = intent.getComponent();
            if (conn == null || component == null
                    || !GMS.equals(component.getPackageName())
                    || !TOKEN_SERVICE.equals(component.getClassName())) {
                return BindServiceHook.Decision.PASS;
            }
            // Not thiz.getPackageName(): a package context made by Play services code
            // would report com.google.android.gms.
            String own = AppCert.packageName();
            if (own == null) {
                return BindServiceHook.Decision.PASS;
            }
            BindServiceHook.deliver(conn, executor, component, new MapsTokenService(own));
            Log.i(TAG, "Maps: answering the token service in-process");
            return BindServiceHook.Decision.FAKE;
        }
    };

    public String id() {
        return "maps_token";
    }

    public String title() {
        return "Google Maps";
    }

    public String description() {
        return "Maps load in shared locations, using WhatsApp's own certificate.";
    }

    public HookCategory category() {
        return HookCategory.INTERFACE;
    }

    public void load() {
        if (!AppCert.active()) {
            Log.i(TAG, "Maps: token service left to Play services (Google API fix off)");
            return;
        }
        // A build patched with -g carries a key of its own, restricted to the
        // patched certificate: Play services already gets a token for that one.
        String key = mapsApiKey(Utils.getApplicationContext());
        String original = Artifacts.get("ORIGINAL_API_KEY");
        if (key != null && original != null && !key.equals(original)) {
            Log.i(TAG, "Maps: the build has its own API key, token service left to Play services");
            return;
        }
        BindServiceHook.register(HANDLER);
        // Plumbing rather than a feature, so it is not in InitProvider.hooks: the
        // one hook that needs it installs it.
        new BindServiceHook().load();
    }

    /** The key the Maps SDK reads from the manifest, or null when it cannot be read. */
    private static String mapsApiKey(Context context) {
        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            Bundle meta = info.metaData;
            for (String name : MAPS_KEY_META_DATA) {
                String key = meta == null ? null : meta.getString(name);
                if (key != null) {
                    return key;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Maps: API key unreadable: " + t);
        }
        return null;
    }

    public void unload() {}
}
