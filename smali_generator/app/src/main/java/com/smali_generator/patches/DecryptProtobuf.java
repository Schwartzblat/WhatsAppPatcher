package com.smali_generator.patches;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Random;

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


    static Object create_message(Object base_message, Object key) {
        Object result = null;
        try {
            Field extended_message_field = base_message.getClass().getDeclaredField("extendedTextMessage_");
            Class<?> ExtendedMessageField = extended_message_field.getType();
            result = ExtendedMessageField.newInstance();
            Field text_field = result.getClass().getDeclaredField("text_");
            text_field.set(result, "Noder Neder");
        } catch (Exception e) {
            Log.e("PATCH", "DecryptProtobuf: Error: " + e.getMessage());
        }
        return result;
    }

    static void handle_delete_message(Object base_message, Object protocol_message) {
        try {
            Field key_field = protocol_message.getClass().getDeclaredField("key_");
            Object key_object = key_field.get(protocol_message);
            if (key_object == null) {
                return;
            }
            Object new_message = create_message(base_message, key_object);
            Log.i("PATCH", "DecryptProtobuf: new_message: " + new_message);
            Field protocol_message_field = base_message.getClass().getDeclaredField("protocolMessage_");
            protocol_message_field.set(base_message, null);
//            Field extended_message_field = base_message.getClass().getDeclaredField("extendedTextMessage_");
//            extended_message_field.set(base_message, new_message);
            Field conversation_field = base_message.getClass().getDeclaredField("conversation_");
            conversation_field.set(base_message, "Noderrrrr");
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
                        Field conversation_field = obj.getClass().getDeclaredField("conversation_");
                        conversation_field.set(obj, "Noderrrrr");
                        Field bit_field0_field = obj.getClass().getDeclaredField("bitField0_");
                        bit_field0_field.set(obj, 67108865);
                        Field message_context_field = obj.getClass().getDeclaredField("messageContextInfo_");
                        Object message_context = message_context_field.getType().newInstance();
                        Object message_secret = message_context.getClass().getDeclaredField("messageSecret_").get(message_context);
                        assert message_secret != null;

                        byte[] bytes = new byte[0x20];
                        new Random().nextBytes(bytes);
                        Log.i("PATCH", "DecryptProtobuf: message_secret_: " + Arrays.toString(bytes));
                        Field bytes_field = message_secret.getClass().getDeclaredField("bytes");
                        bytes_field.setAccessible(true);
                        bytes_field.set(message_secret, bytes);
                        message_context.getClass().getDeclaredField("messageSecret_").set(message_context, message_secret);

                        Object bot_message_secret = message_context.getClass().getDeclaredField("botMessageSecret_").get(message_context);
                        assert bot_message_secret != null;
                        bytes = new byte[0x20];
                        new Random().nextBytes(bytes);
                        Log.i("PATCH", "DecryptProtobuf: botMessageSecret_: " + Arrays.toString(bytes));
                        bytes_field = bot_message_secret.getClass().getDeclaredField("bytes");
                        bytes_field.setAccessible(true);
                        bytes_field.set(bot_message_secret, bytes);
                        message_context.getClass().getDeclaredField("botMessageSecret_").set(message_context, bot_message_secret);

                        Object padding_bytes = message_context.getClass().getDeclaredField("paddingBytes_").get(message_context);
                        assert padding_bytes != null;
                        bytes = new byte[0x20];
                        new Random().nextBytes(bytes);
                        Log.i("PATCH", "DecryptProtobuf: paddingBytes_: " + Arrays.toString(bytes));
                        bytes_field = padding_bytes.getClass().getDeclaredField("bytes");
                        bytes_field.setAccessible(true);
                        bytes_field.set(padding_bytes, bytes);
                        message_context.getClass().getDeclaredField("paddingBytes_").set(message_context, padding_bytes);

                        message_context.getClass().getDeclaredField("bitField0_").set(message_context, 4);
                        message_context_field.set(obj, message_context);
                        Log.i("PATCH", "DecryptProtobuf: conversation_: " + conversation_field.get(obj));
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
