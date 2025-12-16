package com.smali_generator.wrappers;

import android.util.Log;

import com.smali_generator.utils.ReflectionUtils;

import java.lang.reflect.Field;

public class WhatsAppProtobufMessage extends Wrapper {
    public static Field albumMessage_;
    public static Field associatedChildMessage_;
    public static Field audioMessage_;
    public static Field bcallMessage_;
    public static Field bitField0_;
    public static Field bitField1_;
    public static Field bitField2_;
    public static Field botForwardedMessage_;
    public static Field botInvokeMessage_;
    public static Field botTaskMessage_;
    public static Field buttonsMessage_;
    public static Field buttonsResponseMessage_;
    public static Field callLogMesssage_;
    public static Field call_;
    public static Field cancelPaymentRequestMessage_;
    public static Field chat_;
    public static Field commentMessage_;
    public static Field contactMessage_;
    public static Field contactsArrayMessage_;
    public static Field conversation_;
    public static Field declinePaymentRequestMessage_;
    public static Field deviceSentMessage_;
    public static Field documentMessage_;
    public static Field documentWithCaptionMessage_;
    public static Field editedMessage_;
    public static Field encCommentMessage_;
    public static Field encEventResponseMessage_;
    public static Field encReactionMessage_;
    public static Field ephemeralMessage_;
    public static Field eventCoverImage_;
    public static Field eventMessage_;
    public static Field extendedTextMessage_;
    public static Field fastRatchetKeySenderKeyDistributionMessage_;
    public static Field groupInviteMessage_;
    public static Field groupMentionedMessage_;
    public static Field groupStatusMentionMessage_;
    public static Field groupStatusMessageV2_;
    public static Field groupStatusMessage_;
    public static Field highlyStructuredMessage_;
    public static Field imageMessage_;
    public static Field interactiveMessage_;
    public static Field interactiveResponseMessage_;
    public static Field keepInChatMessage_;
    public static Field limitSharingMessage_;
    public static Field listMessage_;
    public static Field listResponseMessage_;
    public static Field liveLocationMessage_;
    public static Field locationMessage_;
    public static Field lottieStickerMessage_;
    public static Field messageContextInfo_;
    public static Field messageHistoryBundle_;
    public static Field messageHistoryNotice_;
    public static Field newsletterAdminInviteMessage_;
    public static Field newsletterAdminProfileMessage_;
    public static Field newsletterFollowerInviteMessageV2_;
    public static Field newsletterFollowerInviteMessage_;
    public static Field orderMessage_;
    public static Field paymentInviteMessage_;
    public static Field pinInChatMessage_;
    public static Field placeholderMessage_;
    public static Field pollCreationMessageV2_;
    public static Field pollCreationMessageV3_;
    public static Field pollCreationMessageV4_;
    public static Field pollCreationMessageV5_;
    public static Field pollCreationMessage_;
    public static Field pollCreationOptionImageMessage_;
    public static Field pollResultSnapshotMessageV3_;
    public static Field pollResultSnapshotMessage_;
    public static Field pollUpdateMessage_;
    public static Field productMessage_;
    public static Field protocolMessage_;
    public static Field ptvMessage_;
    public static Field questionMessage_;
    public static Field questionReplyMessage_;
    public static Field questionResponseMessage_;
    public static Field reactionMessage_;
    public static Field requestPaymentMessage_;
    public static Field requestPhoneNumberMessage_;
    public static Field richResponseMessage_;
    public static Field scheduledCallCreationMessage_;
    public static Field scheduledCallEditMessage_;
    public static Field secretEncryptedMessage_;
    public static Field sendPaymentMessage_;
    public static Field senderKeyDistributionMessage_;
    public static Field statusMentionMessage_;
    public static Field statusNotificationMessage_;
    public static Field statusQuestionAnswerMessage_;
    public static Field statusQuotedMessage_;
    public static Field statusStickerInteractionMessage_;
    public static Field stickerMessage_;
    public static Field stickerPackMessage_;
    public static Field templateButtonReplyMessage_;
    public static Field templateMessage_;
    public static Field videoMessage_;
    public static Field viewOnceMessageV2Extension_;
    public static Field viewOnceMessageV2_;
    public static Field viewOnceMessage_;
    public static Class<?> TYPE_CLASS;

    public WhatsAppProtobufMessage(Object message) {
        this.object = message;
    }

    Class<?> get_type_class() {
        return TYPE_CLASS;
    }

    @SuppressWarnings("unused")
    public static void init() {
        try {
            TYPE_CLASS = E2EMessageParams.protobuf_fields[0].getType();
            for (Field field : TYPE_CLASS.getDeclaredFields()) {
                if ((field.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    Field classField = WhatsAppProtobufMessage.class.getDeclaredField(field.getName());
                    classField.setAccessible(true);
                    classField.set(WhatsAppProtobufMessage.class, field);
                } catch (NoSuchFieldException ignored) {
                    Log.d("PATCH", "WhatsAppProtobufMessage: field not found in wrapper class: " + field.getName());
                } catch (Exception exception) {
                    Log.e("PATCH", "WhatsAppProtobufMessage: error setting field: " + field.getName() + " error: " + exception.getMessage());
                }
            }

            Log.i("PATCH", "WhatsAppProtobufMessage: init success, type class: " + TYPE_CLASS.getName());
        } catch (Exception e) {
            Log.e("PATCH", "WhatsAppProtobufMessage: init error: " + e.getMessage());
        }
    }

    public static WhatsAppProtobufMessage newInstance() {
        try {
            return new WhatsAppProtobufMessage(TYPE_CLASS.getConstructor().newInstance());
        } catch (Exception e) {
            Log.e("PATCH", "WhatsAppProtobufMessage: newInstance error: " + e.getMessage());
            return null;
        }
    }


}
