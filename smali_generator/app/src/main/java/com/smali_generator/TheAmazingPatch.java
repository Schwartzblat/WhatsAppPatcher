package com.smali_generator;

import android.util.Log;

import com.smali_generator.patches.ActivityHook;
import com.smali_generator.patches.DecryptProtobuf;
import com.smali_generator.patches.PackageManagerHook;
import com.smali_generator.patches.ZipFileHook;
import com.smali_generator.wrappers.FMessage;

import java.util.concurrent.atomic.AtomicBoolean;


@SuppressWarnings("unused")
public class TheAmazingPatch {

    static Class<?>[] wrappers = {
            FMessage.class,
    };
    static Hook[] hooks = {
//            new DecryptProtobuf(),
            new PackageManagerHook(),
            new ZipFileHook(),
            new ActivityHook(),
    };

    static AtomicBoolean is_loaded = new AtomicBoolean(false);

    public static void on_load() {
        if (is_loaded.getAndSet(true)) {
            return;
        }

        Log.e("PATCH", "Patch loaded!");

        try {
            for (Class<?> wrapper : wrappers) {
                wrapper.getDeclaredMethod("init").invoke(null);
            }
        } catch (Exception e) {
            Log.e("PATCH", "Error: " + e.getMessage());
        }

        try {
            for (Hook hook : hooks) {
                hook.load();
            }
        } catch (Exception e) {
            Log.e("PATCH", "Error: " + e.getMessage());
        }
    }
}