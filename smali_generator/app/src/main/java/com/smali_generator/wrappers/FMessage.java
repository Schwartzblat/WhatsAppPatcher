package com.smali_generator.wrappers;

import android.util.Log;

import com.smali_generator.utils.ReflectionUtils;

import java.lang.reflect.Field;

public class FMessage extends Wrapper {
    private static Field device_jid;
    public static Class<?> TYPE_CLASS;

    public FMessage(Object fmessage) {
        this.object = fmessage;
    }

    Class<?> get_type_class() {
        return TYPE_CLASS;
    }

    @SuppressWarnings("unused")
    public static void init() {
        try {
            TYPE_CLASS = Class.forName("{{FMESSAGE_CLASS}}");
            Class<?> device_jid_class = Class.forName("com.whatsapp.infra.core.jid.DeviceJid");
            device_jid = ReflectionUtils.findFieldUsingFilter(TYPE_CLASS, field -> field.getType() == device_jid_class);
            Log.i("PATCH", "FMessage: init success");
        } catch (Exception e) {
            Log.e("PATCH", "FMessage: init error: " + e.getMessage());
        }
    }

    public Object getDeviceJid() {
        try {
            return device_jid.get(this.object);
        } catch (Exception e) {
            Log.e(TAG, "FMessage: getDeviceJid error: " + e.getMessage());
        }
        return null;
    }
}
