package com.smali_generator.wrappers;

import android.util.Log;

import java.lang.reflect.Field;

public class MessageContextInfo extends Wrapper {
    public static Field bitField0_;
    public static Field botMessageSecret_;
    public static Field botMetadata_;
    public static Field capiCreatedGroup_;
    public static Field deviceListMetadataVersion_;
    public static Field deviceListMetadata_;
    public static Field limitSharingV2_;
    public static Field limitSharing_;
    public static Field messageAddOnDurationInSecs_;
    public static Field messageAddOnExpiryType_;
    public static Field messageAssociation_;
    public static Field messageSecret_;
    public static Field paddingBytes_;
    public static Field reportingTokenVersion_;
    public static Field supportPayload_;
    public static Field threadIds_;
    public static Field weblinkRenderConfig_;
    public static Class<?> TYPE_CLASS;

    public MessageContextInfo(Object message) {
        this.object = message;
    }

    Class<?> get_type_class() {
        return TYPE_CLASS;
    }

    @SuppressWarnings("unused")
    public static void init() {
        try {
            TYPE_CLASS = WhatsAppProtobufMessage.messageContextInfo_.getType();
            for (Field field : TYPE_CLASS.getDeclaredFields()) {
                if ((field.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    Field classField = MessageContextInfo.class.getDeclaredField(field.getName());
                    classField.setAccessible(true);
                    classField.set(MessageContextInfo.class, field);
                } catch (NoSuchFieldException ignored) {
                    Log.d("PATCH", "MessageContextInfo: field not found in wrapper class: " + field.getName());
                } catch (Exception exception) {
                    Log.e("PATCH", "MessageContextInfo: error setting field: " + field.getName() + " error: " + exception.getMessage());
                }
            }

            Log.i("PATCH", "MessageContextInfo: init success, type class: " + TYPE_CLASS.getName());
        } catch (Exception e) {
            Log.e("PATCH", "MessageContextInfo: init error: " + e.getMessage());
        }
    }


}
