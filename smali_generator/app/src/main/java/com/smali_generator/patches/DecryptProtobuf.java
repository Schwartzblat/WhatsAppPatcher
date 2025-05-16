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

    static void handle_view_once(Object obj) {
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
    }

    static void handle_delete_message(Object base_message, Object protocol_message) {
        try {
            Field key_field = protocol_message.getClass().getDeclaredField("key_");
            Object new_key = key_field.get(protocol_message);
            assert new_key != null;
            new_key.getClass().getDeclaredField("id_").set(new_key, "1234");
            key_field.set(protocol_message, new_key);
            base_message.getClass().getDeclaredField("protocolMessage_").set(base_message, protocol_message);
        } catch (NoSuchFieldException e) {
            Log.e("PATCH", "DecryptProtobuf: field error: " + e.getMessage());
        } catch (Exception e) {
            Log.e("PATCH", "DecryptProtobuf: Error: " + e.getMessage());
        }
    }

    static void handle_protocol_message(Class<?> BaseMessage, Object obj) {
        try {
            Field protocol_message_field = BaseMessage.getDeclaredField("protocolMessage_");
            Object protocol_message = protocol_message_field.get(obj);
            if (protocol_message != null) {
                Field protocol_type = protocol_message.getClass().getDeclaredField("type_");
                Object type_object = protocol_type.get(protocol_message);
                if (type_object == null) {
                    return;
                }
                switch ((int) type_object) {
                    case 0:
                        handle_delete_message(obj, protocol_message);
                        break;
                }
            }
        } catch (NoSuchFieldException e) {
            Log.i("PATCH", "DecryptProtobuf: NoSuchFieldException: " + e.getMessage());
        } catch (Exception e) {
            Log.e("PATCH", "DecryptProtobuf: Error: " + e.getMessage());
        }
    }

    static void handle_final_message(Class<?> MessageClass, Object obj) {
        handle_protocol_message(MessageClass, obj);
    }

    static int decrypt_protobuf_hook(Object self, Object d4o, Object obj, byte[] bArr, int i, int i2, int i3) {
        int ret = DecryptProtobuf.decrypt_protobuf_hook_backup(self, d4o, obj, bArr, i, i2, i3);
        handle_view_once(obj);
        try {
            Class<?> MessageClass = obj.getClass();
            MessageClass.getDeclaredField("protocolMessage_");
            // Should check if the receiver method is expecting a specific type of message because of the previous ones.
            handle_final_message(MessageClass, obj);
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
