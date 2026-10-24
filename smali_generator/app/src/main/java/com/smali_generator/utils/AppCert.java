package com.smali_generator.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.util.Log;

import com.smali_generator.Artifacts;

/**
 * The certificate SHA-1 WhatsApp was signed with before patching (the hash of
 * PACKAGE_SIGNATURE) next to the one it is installed with now. Google API keys
 * can be restricted to the original; the Maps fix only runs when the two differ.
 *
 * init must run before PackageManagerHook loads: from then on getPackageInfo
 * reports PACKAGE_SIGNATURE as the installed certificate too.
 */
public final class AppCert {

    private static final String TAG = "PATCH";

    private static String original;
    private static String installed;
    private static boolean active;
    private static String packageName;

    private AppCert() {}

    public static synchronized void init(Context context) {
        try {
            load(context);
        } catch (Throwable t) {
            original = null;
            installed = null;
            active = false;
            throw t;
        }
    }

    @SuppressWarnings("deprecation")
    private static void load(Context context) {
        original = null;
        installed = null;
        active = false;
        packageName = context.getPackageName();
        String reason = null;
        String o = CertSha1.ofHexDer(Artifacts.get("PACKAGE_SIGNATURE"));
        if (o != null) {
            original = o;
        } else {
            reason = "no original certificate recorded";
        }
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
            Signature[] sigs = info.signatures;
            if (sigs != null && sigs.length > 0) {
                installed = CertSha1.hex(sigs[0].toByteArray());
            } else if (reason == null) {
                reason = "installed certificate unreadable";
            }
        } catch (Throwable t) {
            installed = null;
            if (reason == null) reason = "installed certificate unreadable: " + t;
        }
        active = CertSha1.differs(original, installed);
        if (active) {
            Log.i(TAG, "Cert: original " + original + ", installed " + installed + ": Google API fix on");
        } else {
            if (reason == null) reason = "installed certificate matches the original";
            Log.i(TAG, "Cert: Google API fix off (" + reason + ")");
        }
    }

    public static synchronized String original() {
        return original;
    }

    public static synchronized String installed() {
        return installed;
    }

    /** The app's own package, as read from the provider's context in init. */
    public static synchronized String packageName() {
        return packageName;
    }

    public static synchronized boolean active() {
        return active;
    }
}
