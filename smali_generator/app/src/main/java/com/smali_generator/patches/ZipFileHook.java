package com.smali_generator.patches;

import android.util.Log;

import com.smali_generator.Hook;

import java.lang.reflect.Method;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import lab.galaxy.yahfa.HookMain;


public class ZipFileHook implements Hook {

    static ZipEntry get_entry_hook_backup(ZipFile obj, String entry) {
        return null;
    }

    static ZipEntry get_entry_hook(ZipFile obj, String entry) {
        if (entry.equals("classes.dex")) {
            Log.i("PATCH", "ZipFileHook: Getting entry for: " + entry);
            ZipEntry result = ZipFileHook.get_entry_hook_backup(obj, "classes69.dex");
            Log.i("PATCH", "ZipFileHook: Result: " + result);
            return result;
        }
        return ZipFileHook.get_entry_hook_backup(obj, entry);
    }

    public void load() {
        Log.i("PATCH", "ZipFileHook: Patch loaded");
        try {
            Method get_entry_hook_method = ZipFileHook.class.getDeclaredMethod("get_entry_hook", ZipFile.class, String.class);
            Method get_entry_hook_method_backup = ZipFileHook.class.getDeclaredMethod("get_entry_hook_backup", ZipFile.class, String.class);
            Method original_get_entry = ZipFile.class.getDeclaredMethod("getEntry", String.class);
            HookMain.backupAndHook(original_get_entry, get_entry_hook_method, get_entry_hook_method_backup);
        } catch (Exception e) {
            Log.e("PATCH", "ZipFileHook: Error:" + e.getMessage());
        }
    }

    public void unload() {
        Log.i("PATCH", "PackageManagerHook: Patch unloaded");
    }
}
