package com.smali_generator.patches;

import android.app.Activity;

import android.os.Build;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager.LayoutParams;

import com.smali_generator.Hook;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import lab.galaxy.yahfa.HookMain;


public class ActivityHook implements Hook {

    static void register_screen_capture_hook(Activity activity, Executor executor, Activity.ScreenCaptureCallback callback) {
        Log.i("PATCH", "ActivityHook: registerScreenCaptureCallback has been called");
    }

    static void set_flags_hook_backup(Window window, int flags, int mask) {
    }

    static void set_flags_hook(Window window, int flags, int mask) {
        if ((flags & LayoutParams.FLAG_SECURE) != 0) {
            Log.i("PATCH", "ActivityHook: FLAG_SECURE is set");
            flags ^= LayoutParams.FLAG_SECURE;
            mask ^= LayoutParams.FLAG_SECURE;
        }
        set_flags_hook_backup(window, flags, mask);
    }

    public void load() {
        Log.i("PATCH", "ActivityHook: Patch loaded");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Method register_screen_capture_method = ActivityHook.class.getDeclaredMethod("register_screen_capture_hook", Activity.class, Executor.class, Activity.ScreenCaptureCallback.class);
                Method original_register_screen_capture = Activity.class.getDeclaredMethod("registerScreenCaptureCallback", Executor.class, Activity.ScreenCaptureCallback.class);
                HookMain.hook(original_register_screen_capture, register_screen_capture_method);
            } else {
                Method original_set_flags = Window.class.getMethod("setFlags", int.class, int.class);
                Method set_flags_hook_method = ActivityHook.class.getDeclaredMethod("set_flags_hook", Window.class, int.class, int.class);
                Method set_flags_hook_method_backup = ActivityHook.class.getDeclaredMethod("set_flags_hook_backup", Window.class, int.class, int.class);
                HookMain.backupAndHook(original_set_flags, set_flags_hook_method, set_flags_hook_method_backup);
            }
        } catch (Exception e) {
            Log.e("PATCH", "ActivityHook: Error:" + e.getMessage());
        }
    }

    public void unload() {
        Log.i("PATCH", "ActivityHook: Patch unloaded");
    }
}
