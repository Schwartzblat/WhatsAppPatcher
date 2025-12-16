package com.smali_generator.wrappers;

import android.util.Log;

import java.lang.reflect.Field;

public class MessageKey extends Wrapper {

    public static Field bitField0_;
    public static Field fromMe_;
    public static Field remoteJid_;
    public static Field id_;
    public static Field participant_;
    public static Class<?> TYPE_CLASS;

    public MessageKey(Object message) {
        this.object = message;
    }

    Class<?> get_type_class() {
        return TYPE_CLASS;
    }

    @SuppressWarnings("unused")
    public static void init() {
        try {
            TYPE_CLASS = ProtocolMessage.key_.getType();
            for (Field field : TYPE_CLASS.getDeclaredFields()) {
                if ((field.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    Field classField = MessageKey.class.getDeclaredField(field.getName());
                    classField.setAccessible(true);
                    classField.set(MessageKey.class, field);
                } catch (NoSuchFieldException ignored) {
                    Log.d("PATCH", "MessageKey: field not found in wrapper class: " + field.getName());
                } catch (Exception exception) {
                    Log.e("PATCH", "MessageKey: error setting field: " + field.getName() + " error: " + exception.getMessage());
                }
            }

            Log.i("PATCH", "MessageKey: init success, type class: " + TYPE_CLASS.getName());
        } catch (Exception e) {
            Log.e("PATCH", "MessageKey: init error: " + e.getMessage());
        }
    }


}
