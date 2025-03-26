package com.smali_generator.patches;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import android.util.Log;

import lab.galaxy.yahfa.HookMain;

import com.smali_generator.Hook;


public class DecryptProtobuf implements Hook {

    static int decrypt_protobuf_hook_backup(Object self, Object d4o, Object obj, byte[] bArr, int i, int i2, int i3) {
        return 0;
    }

    static int decrypt_protobuf_hook(Object self, Object d4o, Object obj, byte[] bArr, int i, int i2, int i3) {
        int ret = DecryptProtobuf.decrypt_protobuf_hook_backup(self, d4o, obj, bArr, i, i2, i3);
        try {
            Field view_once_field = obj.getClass().getDeclaredField("viewOnce_");
            view_once_field.setAccessible(true);
            boolean is_view_once = (boolean) view_once_field.get(obj);
            if (is_view_once) {
                view_once_field.set(obj, false);
            }
        } catch (NoSuchFieldException ignored) {
        } catch (Exception e) {
            Log.e("PATCH", "DecryptProtobuf: Error: " + e.getMessage());
        }
        return ret;
    }

    public void load() {
        Log.i("PATCH", "DecryptProtobuf: Patch loaded");
        try {
            Class<?> decrypt_protobuf_class = Class.forName("{{DECRYPT_PROTOBUF_CLASS_NAME}}");
            Method decrypt_protobuf_hook_method = DecryptProtobuf.class.getDeclaredMethod("decrypt_protobuf_hook", Object.class, Object.class, Object.class, byte[].class, int.class, int.class, int.class);
            Method decrypt_protobuf_hook_method_backup = DecryptProtobuf.class.getDeclaredMethod("decrypt_protobuf_hook_backup", Object.class, Object.class, Object.class, byte[].class, int.class, int.class, int.class);
            HookMain.findAndBackupAndHook(decrypt_protobuf_class, "{{DECRYPT_PROTOBUF_METHOD_NAME}}", "{{DECRYPT_PROTOBUF_METHOD_SIG}}", decrypt_protobuf_hook_method, decrypt_protobuf_hook_method_backup);
        } catch (Exception e) {
            Log.e("PATCH", "DecryptProtobuf: Error: " + e.getMessage());
        }
    }

    public void unload() {
        Log.i("PATCH", "DecryptProtobuf: Patch unloaded");
    }
}
