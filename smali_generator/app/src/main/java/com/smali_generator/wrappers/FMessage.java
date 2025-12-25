package com.smali_generator.wrappers;

import android.util.Log;

import com.smali_generator.Wrapper;
import com.smali_generator.utils.ReflectionUtils;

import java.lang.reflect.Field;

public class FMessage implements Wrapper {
    static final String TAG = "PATCH";
    public static Class<?> FMESSAGE_CLASS;
    private static Field device_jid;

    private final Object fmessage;

    public FMessage(Object fmessage) {
        this.fmessage = fmessage;
    }


    @SuppressWarnings("unused")
    public static void init() {
        try {
            FMESSAGE_CLASS = Class.forName("{{FMESSAGE_CLASS}}");
            Class<?> device_jid_class = Class.forName("com.whatsapp.jid.DeviceJid");
            device_jid = ReflectionUtils.findFieldUsingFilter(FMESSAGE_CLASS, field -> field.getType() == device_jid_class);
            Log.i("PATCH", "FMessage: init success");
        } catch (Exception e) {
            Log.e("PATCH", "FMessage: init error: " + e.getMessage());
        }
    }

    public Object getDeviceJid() {
        try {
            return device_jid.get(this.fmessage);
        } catch (Exception e) {
            Log.e(TAG, "FMessage: getDeviceJid error: " + e.getMessage());
        }
        return null;
    }
}
