package com.smali_generator.wrappers;

import android.util.Log;

import com.smali_generator.utils.ReflectionUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Random;

public class MessageSecret extends Wrapper {

    public static Class<?> TYPE_CLASS;

    public MessageSecret(Object secret) {
        this.object = secret;
    }

    Class<?> get_type_class() {
        return TYPE_CLASS;
    }

    @SuppressWarnings("unused")
    public static void init() {
        try {
            TYPE_CLASS = Class.forName("{{MESSAGE_SECRET_CLASS}}");

            Log.i("PATCH", "MessageSecret: init success");
        } catch (Exception e) {
            Log.e("PATCH", "MessageSecret: init error: " + e.getMessage());
        }
    }

    public static Object new_secret() {
        try {
            Constructor<?> constructor = TYPE_CLASS.getConstructors()[0];
            byte[] bytes = new byte[32];
            Random random = new Random();
            random.nextBytes(bytes);
            return constructor.newInstance((Object) bytes);
        } catch (Exception e) {
            Log.e("PATCH", "MessageSecret: new_secret error: " + e.getMessage());
        }
        return null;
    }

}
