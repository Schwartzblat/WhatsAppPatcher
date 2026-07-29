package com.smali_generator.patches;

import android.util.Log;

import com.smali_generator.Hook;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;

import com.arthooks.ArtHooks;


public class FirebaseParams implements Hook {

    static void params_constructor_backup(Object thiz, String applicationId, String apiKey, String databaseUrl, String unknown, String gcmSenderId, String storageBucket, String projectId) {
        return;
    }

    static void params_constructor(Object thiz, String applicationId, String apiKey, String databaseUrl, String unknown, String gcmSenderId, String storageBucket, String projectId) {
        Log.i("PATCH", "FirebaseParams: applicationId: " + applicationId + ", apiKey: " + apiKey);
        apiKey = "{{ORIGINAL_API_KEY}}";
        params_constructor_backup(thiz, applicationId, apiKey, databaseUrl, unknown, gcmSenderId, storageBucket, projectId);
    }

    public void load() {
        Log.i("PATCH", "FirebaseParams: Patch loaded");
        try {
            final String method_sig = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V";
            Class<?> decrypt_protobuf_class = Class.forName("{{FIREBASE_PARAMS_CLASS_NAME}}");
            Method params_constructor = FirebaseParams.class.getDeclaredMethod("params_constructor", Object.class, String.class, String.class, String.class, String.class, String.class, String.class, String.class);
            Method params_constructor_backup = FirebaseParams.class.getDeclaredMethod("params_constructor_backup", Object.class, String.class, String.class, String.class, String.class, String.class, String.class, String.class);
            Executable to_hook = ArtHooks.find_function(decrypt_protobuf_class, "<init>", method_sig);
            ArtHooks.hook_function(params_constructor, to_hook, params_constructor_backup);
        } catch (Exception e) {
            Log.e("PATCH", "FirebaseParams: Error:" + e.getMessage());
        }
    }

    public void unload() {
        Log.i("PATCH", "PackageManagerHook: Patch unloaded");
    }
}
