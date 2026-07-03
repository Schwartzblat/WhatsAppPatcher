package com.smali_generator.patches;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import android.util.Log;

import lab.galaxy.yahfa.HookMain;

import com.smali_generator.Hook;


public class WhatsAppPlus implements Hook {
    static boolean is_premium(Object self, Object feature) {
        return true;
    }

    public void load() {
        Log.i("PATCH", "WhatsAppPlus: Patch loaded");
        try {
            Class<?> whatsapp_plus_class = Class.forName("{{WHATSAPP_PLUS_CLASS_NAME}}");
            Method is_premium_method = WhatsAppPlus.class.getDeclaredMethod("is_premium", Object.class, Object.class);
            HookMain.findAndHook(whatsapp_plus_class, "{{WHATSAPP_PLUS_METHOD_NAME}}", "{{WHATSAPP_PLUS_METHOD_SIG}}", is_premium_method);
        } catch (Exception e) {
            Log.e("PATCH", "WhatsAppPlus: Error: " + e.getMessage());
        }
    }

    public void unload() {
        Log.i("PATCH", "WhatsAppPlus: Patch unloaded");
    }
}
