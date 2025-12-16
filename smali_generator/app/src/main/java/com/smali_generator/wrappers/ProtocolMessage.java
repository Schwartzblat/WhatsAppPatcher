package com.smali_generator.wrappers;

import android.util.Log;

import java.lang.reflect.Field;

public class ProtocolMessage extends Wrapper {

    public static Field aiQueryFanout_;
    public static Field appStateFatalExceptionNotification_;
    public static Field appStateSyncKeyRequest_;
    public static Field appStateSyncKeyShare_;
    public static Field bitField0_;
    public static Field botFeedbackMessage_;
    public static Field cloudApiThreadControlNotification_;
    public static Field disappearingMode_;
    public static Field editedMessage_;
    public static Field ephemeralExpiration_;
    public static Field ephemeralSettingTimestamp_;
    public static Field historySyncNotification_;
    public static Field initialSecurityNotificationSettingSync_;
    public static Field key_;
    public static Field lidMigrationMappingSyncMessage_;
    public static Field limitSharing_;
    public static Field mediaNotifyMessage_;
    public static Field memberLabel_;
    public static Field peerDataOperationRequestMessage_;
    public static Field peerDataOperationRequestResponseMessage_;
    public static Field timestampMs_;
    public static Field type_;
    public static Field invokerJid_;
    public static Field aiPsiMetadata_;
    public static Class<?> TYPE_CLASS;

    public ProtocolMessage(Object message) {
        this.object = message;
    }

    Class<?> get_type_class() {
        return TYPE_CLASS;
    }

    @SuppressWarnings("unused")
    public static void init() {
        try {
            TYPE_CLASS = WhatsAppProtobufMessage.protocolMessage_.getType();
            for (Field field : TYPE_CLASS.getDeclaredFields()) {
                if ((field.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    Field classField = ProtocolMessage.class.getDeclaredField(field.getName());
                    classField.setAccessible(true);
                    classField.set(ProtocolMessage.class, field);
                } catch (NoSuchFieldException ignored) {
                    Log.d("PATCH", "ProtocolMessage: field not found in wrapper class: " + field.getName());
                } catch (Exception exception) {
                    Log.e("PATCH", "ProtocolMessage: error setting field: " + field.getName() + " error: " + exception.getMessage());
                }
            }

            Log.i("PATCH", "ProtocolMessage: init success, type class: " + TYPE_CLASS.getName());
        } catch (Exception e) {
            Log.e("PATCH", "ProtocolMessage: init error: " + e.getMessage());
        }
    }


}
