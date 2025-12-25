package com.smali_generator.wrappers;

import android.util.Log;

import com.smali_generator.utils.ReflectionUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Type;

public class E2EMessageParams extends Wrapper {
    public static Field[] protobuf_fields;
    public static Class<?> TYPE_CLASS;

    public static Field editedVersion;
    public static Field isQuoted;

    public E2EMessageParams(Object e2EMessageParams) {
        this.object = e2EMessageParams;
    }

    Class<?> get_type_class() {
        return TYPE_CLASS;
    }

    @SuppressWarnings("unused")
    public static void init() {
        try {
            TYPE_CLASS = Class.forName("{{E2EMESSAGE_PARAMS_CLASS}}");
            protobuf_fields = ReflectionUtils.findAllFieldsUsingFilter(TYPE_CLASS, field -> {
                try {
                    field.getType().getDeclaredField("conversation_");
                    return true;
                } catch (NoSuchFieldException ignored) {
                }
                return false;
            });
            editedVersion = TYPE_CLASS.getDeclaredField("{{EDITED_VERSION_FIELD}}");
            isQuoted = TYPE_CLASS.getDeclaredField("{{INCLUDE_QUOTED_FIELD}}");
            Log.i("PATCH", "E2EMessageParams: found protobuf fields count: " + protobuf_fields.length);
            Log.i("PATCH", "E2EMessageParams: init success");
        } catch (Exception e) {
            Log.e("PATCH", "E2EMessageParams: init error: " + e.getMessage());
        }
    }

    public WhatsAppProtobufMessage getProtobuf() {
        return new WhatsAppProtobufMessage(get_field(protobuf_fields[0]));
    }

    public void setProtobufFields(WhatsAppProtobufMessage protobuf) {
        set(protobuf_fields[0], protobuf.object);
        set(protobuf_fields[1], protobuf.object);
    }
}
