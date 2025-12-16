package com.smali_generator;

import android.util.Log;

import com.smali_generator.patches.ActivityHook;
import com.smali_generator.patches.DecryptProtobuf;
import com.smali_generator.patches.FMessageProtobufHook;
import com.smali_generator.patches.PackageManagerHook;
import com.smali_generator.patches.ZipFileHook;
import com.smali_generator.wrappers.ContextInfo;
import com.smali_generator.wrappers.E2EMessageParams;
import com.smali_generator.wrappers.ExtendedTextMessage;
import com.smali_generator.wrappers.FMessage;
import com.smali_generator.wrappers.MessageContextInfo;
import com.smali_generator.wrappers.MessageKey;
import com.smali_generator.wrappers.MessageSecret;
import com.smali_generator.wrappers.ProtocolMessage;
import com.smali_generator.wrappers.WhatsAppProtobufMessage;

import java.util.concurrent.atomic.AtomicBoolean;


@SuppressWarnings("unused")
public class TheAmazingPatch {

    static Class<?>[] wrappers = {
            FMessage.class,
            E2EMessageParams.class,
            WhatsAppProtobufMessage.class,
            ProtocolMessage.class,
            MessageContextInfo.class,
            MessageSecret.class,
            ExtendedTextMessage.class,
            ContextInfo.class,
            MessageKey.class,
    };
    static Hook[] hooks = {
            new DecryptProtobuf(),
            new PackageManagerHook(),
            new ZipFileHook(),
            new ActivityHook(),
            new FMessageProtobufHook(),
    };

    static AtomicBoolean is_loaded = new AtomicBoolean(false);

    public static void on_load() {
        if (is_loaded.getAndSet(true)) {
            return;
        }

        Log.e("PATCH", "Patch loaded!");

        try {
            for (Class<?> wrapper : wrappers) {
                wrapper.getDeclaredMethod("init").invoke(null);
            }
        } catch (Exception e) {
            Log.e("PATCH", "Error: " + e.getMessage());
        }

        try {
            for (Hook hook : hooks) {
                hook.load();
            }
        } catch (Exception e) {
            Log.e("PATCH", "Error: " + e.getMessage());
        }
    }
}