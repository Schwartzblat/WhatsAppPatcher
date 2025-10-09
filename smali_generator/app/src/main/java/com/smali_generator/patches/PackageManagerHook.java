package com.smali_generator.patches;

import android.annotation.SuppressLint;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.content.pm.VersionedPackage;
import android.os.Build;
import android.util.Log;

import com.smali_generator.Hook;
import com.smali_generator.utils.Utils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Objects;

import lab.galaxy.yahfa.HookMain;


public class PackageManagerHook implements Hook {
    static PackageInfo get_package_info_hook_backup(PackageManager obj, VersionedPackage package_name, int flags) {
        Log.e("PATCH", "PackageManagerHook: WTF get_package_info_hook_backup(VersionedPackage, int) called");
        return null;
    }

    static PackageInfo get_package_info_hook_backup(PackageManager obj, VersionedPackage package_name, PackageManager.PackageInfoFlags flags) {
        Log.e("PATCH", "PackageManagerHook: WTF get_package_info_hook_backup(VersionedPackage, int) called");
        return null;
    }

    static PackageInfo get_package_info_hook_backup(PackageManager obj, String package_name, PackageManager.PackageInfoFlags flags) {
        Log.e("PATCH", "PackageManagerHook: WTF get_package_info_hook_backup(String, PackageInfoFlags) called");
        return null;
    }

    static PackageInfo get_package_info_hook(PackageManager obj, String package_name, PackageManager.PackageInfoFlags flags) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.e("PATCH", "PackageManagerHook: Unsupported flags type: " + flags);
            throw new RuntimeException("Unsupported flags type");
        }
        PackageInfo package_info = PackageManagerHook.get_package_info_hook_backup(obj, package_name, flags);
        if (package_info == null) {
            try {
                package_info = obj.getPackageInfo(new VersionedPackage(package_name, -1), flags);
            } catch (Exception e) {
                Log.e("PATCH", "PackageManagerHook: Error getting package info: " + e.getMessage());
                return null;
            }
        }
        Log.i("PATCH", "PackageManagerHook: package_info: " + package_info + ", package_name: " + package_name + ", flags: " + flags.getValue());
        if (package_name.equals("com.whatsapp") && ((flags.getValue() & 64) != 0 || (flags.getValue() & 0x8000000) != 0) && package_info != null) {
            Log.i("PATCH", "PackageManagerHook: Replacing package info...");
            package_info.signatures = new Signature[]{new Signature("{{PACKAGE_SIGNATURE}}")};
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    package_info.signingInfo = new SigningInfo(2, Collections.singletonList(new Signature("{{PACKAGE_SIGNATURE}}")), null, null);
                } else {
                    Class<?> SigningInfoClass = Class.forName("android.content.pm.SigningInfo");
                    // Is this field actually exist?
                    @SuppressLint("SoonBlockedPrivateApi") Field mSigningDetails = SigningInfoClass.getDeclaredField("mSigningDetails");
                    mSigningDetails.setAccessible(true);
                    Object signing_details = mSigningDetails.get(package_info.signingInfo);
                    assert signing_details != null;
                    Field signatures = signing_details.getClass().getDeclaredField("signatures");
                    signatures.setAccessible(true);
                    signatures.set(signing_details, new Signature[]{new Signature("{{PACKAGE_SIGNATURE}}")});
                }
            } catch (Exception e) {
                Log.e("PATCH", "PackageManagerHook: Error: " + e.getMessage());
            }
        }
        return package_info;
    }

    static PackageInfo get_package_info_hook(PackageManager obj, VersionedPackage versioned_package, int flags) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.e("PATCH", "PackageManagerHook: Unsupported flags type: " + flags);
            throw new RuntimeException("Unsupported flags type");
        }
        String package_name = versioned_package.getPackageName();
        PackageInfo package_info = PackageManagerHook.get_package_info_hook_backup(obj, versioned_package, flags);
        Log.e("PATCH", "PackageManagerHook: package_info: " + package_info + ", package_name: " + package_name + ", flags: " + flags);
        if (package_name.equals("com.whatsapp") && ((flags & 64) != 0 || (flags & 0x8000000) != 0) && package_info != null) {
            Log.i("PATCH", "PackageManagerHook: Replacing package info...");
            package_info.signatures = new Signature[]{new Signature("{{PACKAGE_SIGNATURE}}")};
            try {
                Class<?> SigningInfoClass = Class.forName("android.content.pm.SigningInfo");
                // Is this field actually exist?
                @SuppressLint("SoonBlockedPrivateApi") Field mSigningDetails = SigningInfoClass.getDeclaredField("mSigningDetails");
                mSigningDetails.setAccessible(true);
                Object signing_details = mSigningDetails.get(package_info.signingInfo);
                assert signing_details != null;
                Field signatures = signing_details.getClass().getDeclaredField("signatures");
                signatures.setAccessible(true);
                signatures.set(signing_details, new Signature[]{new Signature("{{PACKAGE_SIGNATURE}}")});
            } catch (Exception e) {
                Log.e("PATCH", "PackageManagerHook: Error: " + e.getMessage());
            }
        }
        return package_info;
    }

    static PackageInfo get_package_info_hook(PackageManager obj, VersionedPackage versioned_package, PackageManager.PackageInfoFlags flags) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.e("PATCH", "PackageManagerHook: Unsupported flags type: " + flags);
            throw new RuntimeException("Unsupported flags type");
        }
        String package_name = versioned_package.getPackageName();
        PackageInfo package_info = PackageManagerHook.get_package_info_hook_backup(obj, versioned_package, flags);
        Log.e("PATCH", "PackageManagerHook: package_info: " + package_info + ", package_name: " + package_name + ", flags: " + flags.getValue());
        if (package_name.equals("com.whatsapp") && ((flags.getValue() & 64) != 0 || (flags.getValue() & 0x8000000) != 0) && package_info != null) {
            Log.i("PATCH", "PackageManagerHook: Replacing package info...");
            package_info.signatures = new Signature[]{new Signature("{{PACKAGE_SIGNATURE}}")};
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    package_info.signingInfo = new SigningInfo(2, Collections.singletonList(new Signature("{{PACKAGE_SIGNATURE}}")), null, null);
                } else {
                    Class<?> SigningInfoClass = Class.forName("android.content.pm.SigningInfo");
                    // Is this field actually exist?
                    @SuppressLint("SoonBlockedPrivateApi") Field mSigningDetails = SigningInfoClass.getDeclaredField("mSigningDetails");
                    mSigningDetails.setAccessible(true);
                    Object signing_details = mSigningDetails.get(package_info.signingInfo);
                    assert signing_details != null;
                    Field signatures = signing_details.getClass().getDeclaredField("signatures");
                    signatures.setAccessible(true);
                    signatures.set(signing_details, new Signature[]{new Signature("{{PACKAGE_SIGNATURE}}")});
                }
            } catch (Exception e) {
                Log.e("PATCH", "PackageManagerHook: Error: " + e.getMessage());
            }
        }
        return package_info;
    }

    public void load() {
        Log.i("PATCH", "PackageManagerHook: Patch loaded");
        try {
            @SuppressLint("PrivateApi") Class<?> ApplicationPackageManager = Class.forName("android.app.ApplicationPackageManager");

            Method get_package_info_hook_method;
            Method get_package_info_hook_method_backup;
            Method original_get_package_info;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                get_package_info_hook_method = PackageManagerHook.class.getDeclaredMethod("get_package_info_hook", PackageManager.class, VersionedPackage.class, PackageManager.PackageInfoFlags.class);
                get_package_info_hook_method_backup = PackageManagerHook.class.getDeclaredMethod("get_package_info_hook_backup", PackageManager.class, VersionedPackage.class, PackageManager.PackageInfoFlags.class);
                original_get_package_info = ApplicationPackageManager.getDeclaredMethod("getPackageInfo", VersionedPackage.class, PackageManager.PackageInfoFlags.class);
                HookMain.backupAndHook(original_get_package_info, get_package_info_hook_method, get_package_info_hook_method_backup);

                get_package_info_hook_method = PackageManagerHook.class.getDeclaredMethod("get_package_info_hook", PackageManager.class, String.class, PackageManager.PackageInfoFlags.class);
                get_package_info_hook_method_backup = PackageManagerHook.class.getDeclaredMethod("get_package_info_hook_backup", PackageManager.class, String.class, PackageManager.PackageInfoFlags.class);
                original_get_package_info = ApplicationPackageManager.getDeclaredMethod("getPackageInfo", String.class, PackageManager.PackageInfoFlags.class);

            } else {
                Log.i("PATCH", "PackageManagerHook: using old versioned package method");
                get_package_info_hook_method = PackageManagerHook.class.getDeclaredMethod("get_package_info_hook", PackageManager.class, VersionedPackage.class, int.class);
                get_package_info_hook_method_backup = PackageManagerHook.class.getDeclaredMethod("get_package_info_hook_backup", PackageManager.class, VersionedPackage.class, int.class);
                original_get_package_info = ApplicationPackageManager.getDeclaredMethod("getPackageInfo", VersionedPackage.class, int.class);
            }
            HookMain.backupAndHook(original_get_package_info, get_package_info_hook_method, get_package_info_hook_method_backup);
        } catch (Exception e) {
            Log.e("PATCH", "PackageManagerHook: Error:" + e.getMessage());
        }
    }

    public void unload() {
        Log.i("PATCH", "PackageManagerHook: Patch unloaded");
    }
}
