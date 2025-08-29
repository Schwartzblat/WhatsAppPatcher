package com.smali_generator.patches;

import android.annotation.SuppressLint;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.util.Log;
import com.smali_generator.utils.Utils;
import com.smali_generator.Hook;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Objects;
import lab.galaxy.yahfa.HookMain;


public class PackageManagerHook implements Hook {

    static PackageInfo get_package_info_hook_backup(PackageManager obj, String package_name, int flags) {
        return null;
    }

    static PackageInfo get_package_info_hook(PackageManager obj, String package_name, int flags) {
        PackageInfo package_info = PackageManagerHook.get_package_info_hook_backup(obj, package_name, flags);
        Log.e("PATCH", "PackageManagerHook: package_info: " + package_info + ", package_name: " + package_name + ", flags: " + flags);
        if (package_info == null) {
            try {
                package_info = PackageManagerHook.get_package_info_hook_backup(Objects.requireNonNull(Utils.getApplication()).getApplicationContext().getPackageManager(), package_name, flags);
                Log.i("PATCH", "PackageManagerHook: new package_info: " + package_info);
            } catch (Exception e) {
                Log.e("PATCH", "PackageManagerHook: Error: " + e.getMessage());
            }
        }
        if (package_name.equals("com.whatsapp") && (flags & 0x8000000) != 0 && package_info != null) {
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
            @SuppressLint("PrivateApi") Class<?> decrypt_protobuf_class = Class.forName("android.app.ApplicationPackageManager");
            Method get_package_info_hook_method = PackageManagerHook.class.getDeclaredMethod("get_package_info_hook", PackageManager.class, String.class, int.class);
            Method get_package_info_hook_method_backup = PackageManagerHook.class.getDeclaredMethod("get_package_info_hook_backup", PackageManager.class, String.class, int.class);
            Method original_get_package_info = decrypt_protobuf_class.getDeclaredMethod("getPackageInfo", String.class, int.class);
            HookMain.backupAndHook(original_get_package_info, get_package_info_hook_method, get_package_info_hook_method_backup);
        } catch (Exception e) {
            Log.e("PATCH", "PackageManagerHook: Error:" + e.getMessage());
        }
    }

    public void unload() {
        Log.i("PATCH", "PackageManagerHook: Patch unloaded");
    }
}
