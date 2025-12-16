package com.smali_generator.patches;

import android.app.Activity;
import android.os.Build;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import com.smali_generator.Hook;
import com.smali_generator.wrappers.ContextInfo;
import com.smali_generator.wrappers.E2EMessageParams;
import com.smali_generator.wrappers.ExtendedTextMessage;
import com.smali_generator.wrappers.FMessage;
import com.smali_generator.wrappers.MessageContextInfo;
import com.smali_generator.wrappers.MessageKey;
import com.smali_generator.wrappers.MessageSecret;
import com.smali_generator.wrappers.ProtocolMessage;
import com.smali_generator.wrappers.WhatsAppProtobufMessage;
import com.smali_generator.wrappers.Wrapper;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;

import lab.galaxy.yahfa.HookMain;

public class FMessageProtobufHook implements Hook {


    static Object generate_fmessage_backup(Object self, Object e2EMessageParams) {
        return null;
    }

    static Object generate_fmessage(Object self, Object e2EMessageParams) {
        Log.i("PATCH", "FMessageProtobufHook: generate_fmessage called");
        try {
            E2EMessageParams params = new E2EMessageParams(e2EMessageParams);
            WhatsAppProtobufMessage message = params.getProtobuf();
            ProtocolMessage protocol_message = new ProtocolMessage(message.get(WhatsAppProtobufMessage.protocolMessage_));
            if (protocol_message.object != null) {
                if ((int)protocol_message.get("type_") == 0) {
                    Log.i("PATCH", "FMessageProtobufHook: replacing protocol_message...");
                    params.set(E2EMessageParams.editedVersion, 0);
                    message.set(WhatsAppProtobufMessage.bitField0_, 67108865);
                    message.set(WhatsAppProtobufMessage.protocolMessage_, null);
                    MessageContextInfo messageContextInfo = new MessageContextInfo(message.get(WhatsAppProtobufMessage.messageContextInfo_));
                    messageContextInfo.set(MessageContextInfo.bitField0_, 8196);
                    messageContextInfo.set(MessageContextInfo.messageSecret_, MessageSecret.new_secret());

                    ExtendedTextMessage extended_message = ExtendedTextMessage.newInstance();
                    assert extended_message != null;
                    extended_message.set(ExtendedTextMessage.text_, "🚫 This message was deleted!");
                    extended_message.set(ExtendedTextMessage.bitField0_, 2097665);

                    ContextInfo contextInfo = ContextInfo.newInstance();
                    assert contextInfo != null;
                    contextInfo.set(ContextInfo.bitField0_, 262151);

                    WhatsAppProtobufMessage quoted_message = WhatsAppProtobufMessage.newInstance();
                    assert quoted_message != null;
                    quoted_message.set(WhatsAppProtobufMessage.bitField0_, 67108865);
                    quoted_message.set(WhatsAppProtobufMessage.conversation_, "What the hell?!");
                    MessageKey messageKey = new MessageKey(protocol_message.get(ProtocolMessage.key_));
                    contextInfo.set(ContextInfo.stanzaId_, messageKey.get(MessageKey.id_));

                    Log.i("PATCH", "FMessageProtobufHook: quoted message id: " + messageKey.get(MessageKey.id_));

                    contextInfo.set(ContextInfo.quotedMessage_, quoted_message.object);
                    extended_message.set(ExtendedTextMessage.contextInfo_, contextInfo.object);
                    message.set(WhatsAppProtobufMessage.extendedTextMessage_, extended_message.object);
                    params.setProtobufFields(message);
                }
            } else {
                Log.i("PATCH", "FMessageProtobufHook: conversation: " + message.get("conversation_"));
            }
        } catch (Exception e) {
            Log.e("PATCH", "FMessageProtobufHook: generate_fmessage error: " + e.getMessage());
        }
        Object ret = generate_fmessage_backup(self, e2EMessageParams);
        Log.i("PATCH", "FMessageProtobufHook: generate_fmessage returned " + ret);
        return ret;
    }
    static void notify_incoming_message_backup(Object self, Object incomingMessageStanza, Object message, Object messageContextInfo, Object fMessage, byte[] bArr) {
        Log.i("PATCH", "FMessageProtobufHook: notify_incoming_message called");
    }
    static void notify_incoming_message(Object self, Object incomingMessageStanza, Object message, Object messageContextInfo, Object fMessage, byte[] bArr) {
        Log.i("PATCH", "FMessageProtobufHook: notify_incoming_message called");
        notify_incoming_message_backup(self, incomingMessageStanza, message, messageContextInfo, fMessage, bArr);
        FMessage fmessage = new FMessage(fMessage);
        if ((Integer)fmessage.get("A00") == 7) {
            Log.i("PATCH", "FMessageProtobufHook: resetting A00 from 7 to 0");
            fmessage.set("A00", 0);
        }

    }

    public void load() {
        Log.i("PATCH", "FMessageProtobufHook: Patch loaded");
        try {
            Class<?> fmessage_protobuf_class = Class.forName("{{FMESSAGE_PROTOBUF_CLASS_NAME}}");
            Method fmessage_protobuf_hook_method = FMessageProtobufHook.class.getDeclaredMethod("generate_fmessage", Object.class, Object.class);
            Method fmessage_protobuf_hook_method_backup = FMessageProtobufHook.class.getDeclaredMethod("generate_fmessage_backup", Object.class, Object.class);
            HookMain.findAndBackupAndHook(fmessage_protobuf_class, "{{FMESSAGE_PROTOBUF_METHOD_NAME}}", "{{FMESSAGE_PROTOBUF_METHOD_SIG}}", fmessage_protobuf_hook_method, fmessage_protobuf_hook_method_backup);
            Log.i("PATCH", "FMessageProtobufHook: Hooked FmessageProtobuf");

            Class<?> incoming_manager = Class.forName("{{INCOMING_MANAGER_CLASS_NAME}}");
            Method notify_message_hook_method = FMessageProtobufHook.class.getDeclaredMethod("notify_incoming_message", Object.class, Object.class, Object.class, Object.class, Object.class, byte[].class);
            Method notify_message_hook_method_backup = FMessageProtobufHook.class.getDeclaredMethod("notify_incoming_message_backup", Object.class, Object.class, Object.class, Object.class, Object.class, byte[].class);
            HookMain.findAndBackupAndHook(incoming_manager, "{{INCOMING_MANAGER_METHOD_NAME}}", "{{INCOMING_MANAGER_METHOD_SIG}}", notify_message_hook_method, notify_message_hook_method_backup);
            Log.i("PATCH", "FMessageProtobufHook: Hooked IncomingManager");


        } catch (Exception e) {
            Log.e("PATCH", "FMessageProtobufHook: Error:" + e.getMessage());
        }
    }

    public void unload() {
        Log.i("PATCH", "FMessageProtobufHook: Patch unloaded");
    }
}
