package com.smali_generator.wrappers;

import android.util.Log;

import java.lang.annotation.Target;
import java.lang.reflect.Field;

public class ExtendedTextMessage extends Wrapper {

    public static Field backgroundArgb_;
    public static Field bitField0_;
    public static Field contextInfo_;
    public static Field doNotPlayInline_;
    public static Field faviconMMSMetadata_;
    public static Field font_;
    public static Field inviteLinkGroupTypeV2_;
    public static Field inviteLinkGroupType_;
    public static Field inviteLinkParentGroupSubjectV2_;
    public static Field inviteLinkParentGroupThumbnailV2_;
    public static Field jpegThumbnail_;
    public static Field linkPreviewMetadata_;
    public static Field mediaKeyTimestamp_;
    public static Field mediaKey_;
    public static Field musicMetadata_;
    public static Field paymentExtendedMetadata_;
    public static Field paymentLinkMetadata_;
    public static Field previewType_;
    public static Field textArgb_;
    public static Field thumbnailDirectPath_;
    public static Field thumbnailEncSha256_;
    public static Field thumbnailHeight_;
    public static Field thumbnailSha256_;
    public static Field thumbnailWidth_;
    public static Field videoContentUrl_;
    public static Field videoHeight_;
    public static Field videoWidth_;
    public static Field viewOnce_;
    public static Field text_;
    public static Field matchedText_;
    public static Field description_;
    public static Field title_;
    public static Class<?> TYPE_CLASS;

    public ExtendedTextMessage(Object message) {
        this.object = message;
    }

    Class<?> get_type_class() {
        return TYPE_CLASS;
    }

    @SuppressWarnings("unused")
    public static void init() {
        try {
            TYPE_CLASS = WhatsAppProtobufMessage.extendedTextMessage_.getType();
            for (Field field : TYPE_CLASS.getDeclaredFields()) {
                if ((field.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    Field classField = ExtendedTextMessage.class.getDeclaredField(field.getName());
                    classField.setAccessible(true);
                    classField.set(ExtendedTextMessage.class, field);
                } catch (NoSuchFieldException ignored) {
                    Log.d("PATCH", "ExtendedTextMessage: field not found in wrapper class: " + field.getName());
                } catch (Exception exception) {
                    Log.e("PATCH", "ExtendedTextMessage: error setting field: " + field.getName() + " error: " + exception.getMessage());
                }
            }

            Log.i("PATCH", "ExtendedTextMessage: init success, type class: " + TYPE_CLASS.getName());
        } catch (Exception e) {
            Log.e("PATCH", "ExtendedTextMessage: init error: " + e.getMessage());
        }
    }

    public static ExtendedTextMessage newInstance() {
        try {
            return new ExtendedTextMessage(TYPE_CLASS.getConstructor().newInstance());
        } catch (Exception e) {
            Log.e("PATCH", "ExtendedTextMessage: newInstance error: " + e.getMessage());
            return null;
        }
    }


}
