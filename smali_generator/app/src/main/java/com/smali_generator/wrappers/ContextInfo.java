package com.smali_generator.wrappers;

import android.util.Log;

import java.lang.reflect.Field;

public class ContextInfo extends Wrapper {

    public static Field actionLink_;
    public static Field alwaysShowAdAttribution_;
    public static Field bitField0_;
    public static Field bitField1_;
    public static Field botMessageSharingInfo_;
    public static Field businessMessageForwardInfo_;
    public static Field conversionData_;
    public static Field conversionDelaySeconds_;
    public static Field conversionSource_;
    public static Field ctwaPayload_;
    public static Field ctwaSignals_;
    public static Field dataSharingContext_;
    public static Field disappearingMode_;
    public static Field entryPointConversionApp_;
    public static Field entryPointConversionDelaySeconds_;
    public static Field entryPointConversionExternalMedium_;
    public static Field entryPointConversionExternalSource_;
    public static Field entryPointConversionSource_;
    public static Field ephemeralSettingTimestamp_;
    public static Field ephemeralSharedSecret_;
    public static Field expiration_;
    public static Field externalAdReply_;
    public static Field featureEligibilities_;
    public static Field forwardOrigin_;
    public static Field forwardedAiBotMessageInfo_;
    public static Field forwardedNewsletterMessageInfo_;
    public static Field forwardingScore_;
    public static Field groupMentions_;
    public static Field groupSubject_;
    public static Field isForwarded_;
    public static Field isGroupStatus_;
    public static Field isQuestion_;
    public static Field isSampled_;
    public static Field memberLabel_;
    public static Field mentionedJid_;
    public static Field nonJidMentions_;
    public static Field pairedMediaType_;
    public static Field parentGroupJid_;
    public static Field placeholderKey_;
    public static Field questionReplyQuotedMessage_;
    public static Field quotedAd_;
    public static Field quotedMessage_;
    public static Field quotedType_;
    public static Field rankingVersion_;
    public static Field smbClientCampaignId_;
    public static Field statusAttributionType_;
    public static Field statusAttributions_;
    public static Field statusAudienceMetadata_;
    public static Field statusSourceType_;
    public static Field trustBannerAction_;
    public static Field trustBannerType_;
    public static Field urlTrackingMap_;
    public static Field stanzaId_;
    public static Field participant_;
    public static Field remoteJid_;
    public static Class<?> TYPE_CLASS;

    public ContextInfo(Object message) {
        this.object = message;
    }

    Class<?> get_type_class() {
        return TYPE_CLASS;
    }

    @SuppressWarnings("unused")
    public static void init() {
        try {
            TYPE_CLASS = ExtendedTextMessage.contextInfo_.getType();
            for (Field field : TYPE_CLASS.getDeclaredFields()) {
                if ((field.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    Field classField = ContextInfo.class.getDeclaredField(field.getName());
                    classField.setAccessible(true);
                    classField.set(ContextInfo.class, field);
                } catch (NoSuchFieldException ignored) {
                    Log.d("PATCH", "ContextInfo: field not found in wrapper class: " + field.getName());
                } catch (Exception exception) {
                    Log.e("PATCH", "ContextInfo: error setting field: " + field.getName() + " error: " + exception.getMessage());
                }
            }

            Log.i("PATCH", "ContextInfo: init success, type class: " + TYPE_CLASS.getName());
        } catch (Exception e) {
            Log.e("PATCH", "ContextInfo: init error: " + e.getMessage());
        }
    }

    public static ContextInfo newInstance() {
        try {
            return new ContextInfo(TYPE_CLASS.getConstructor().newInstance());
        } catch (Exception e) {
            Log.e("PATCH", "ContextInfo: newInstance error: " + e.getMessage());
            return null;
        }
    }


}
