package com.smali_generator;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import lab.galaxy.yahfa.HookMain;


@SuppressWarnings("unused")
public class TheAmazingPatch {
    static AtomicBoolean is_loaded = new AtomicBoolean(false);

    static int decrypt_protobuf_hook(Object self, Object d4o, Object obj, byte[] bArr, int i, int i2, int i3) {
        Log.e("PATCH", "decrypt_protobuf called: bArr:" + Arrays.toString(bArr));
        int ret = TheAmazingPatch.decrypt_protobuf_hook_backup(self, d4o, obj, bArr, i, i2, i3);
        Log.e("PATCH", "decrypt_protobuf returned: " + obj);
        try {
            Field view_once_field = obj.getClass().getDeclaredField("viewOnce_");
            view_once_field.setAccessible(true);
            boolean is_view_once = (boolean)view_once_field.get(obj);
            if (is_view_once) {
                Log.e("PATCH", "viewOnce_ is true");
                view_once_field.set(obj, false);
            }
        } catch (Exception e) {
            Log.e("PATCH", "Error: " + e.getMessage());
        }
        return ret;
    }

    static int decrypt_protobuf_hook_backup(Object self, Object d4o, Object obj, byte[] bArr, int i, int i2, int i3) {
        return 0;
    }

    public static void on_load() {
        if (is_loaded.getAndSet(true)) {
            return;
        }
        Log.e("PATCH", "Patch loaded, {{SOME_CONST_KEY}}");
        try {
            Class<?> main_activity = Class.forName("X.Dq4");
            Method decrypt_protobuf_hook_method = TheAmazingPatch.class.getDeclaredMethod("decrypt_protobuf_hook", Object.class, Object.class, Object.class, byte[].class, int.class, int.class, int.class);
            Method decrypt_protobuf_hook_method_backup = TheAmazingPatch.class.getDeclaredMethod("decrypt_protobuf_hook_backup", Object.class, Object.class, Object.class, byte[].class, int.class, int.class, int.class);
            HookMain.findAndBackupAndHook(main_activity, "A0f", "(LX/D4O;Ljava/lang/Object;[BIII)I", decrypt_protobuf_hook_method, decrypt_protobuf_hook_method_backup);
        } catch (Exception e) {
            Log.e("PATCH", "Error: " + e.getMessage());
        }
    }
}