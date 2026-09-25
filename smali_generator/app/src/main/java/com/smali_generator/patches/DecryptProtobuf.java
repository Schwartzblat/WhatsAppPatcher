package com.smali_generator.patches;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import android.util.Log;

import com.arthooks.ArtHooks;

import com.smali_generator.Hook;
import com.smali_generator.HookCategory;
import com.smali_generator.db.MessageKey;
import com.smali_generator.db.PatchDb;


/**
 * Keeps deleted and view-once messages, by rewriting the protobuf on its way in.
 *
 * The target is the builder of WhatsApp's ParseE2EMessageParams, not the
 * message class's own parseFrom. parseFrom is a one-line static forwarder, so
 * dex2oat inlines it into every call site: an entry-point hook on it stops
 * being reached the moment background dexopt compiles the app, and stops
 * silently -- the load line still says hooked, and the replacement is simply
 * never entered again. A fresh install hides it, because installing leaves the
 * app at status=verify. The build method here is ~78 instructions, far past
 * anything ART will inline, and it is the last point on the receive path where
 * the protobuf is still writable.
 */
public class DecryptProtobuf implements Hook {

    private static final String TAG = "PATCH";

    /**
     * What a revoked message's key id is replaced with.
     *
     * WhatsApp looks its own message up by this id and finds nothing, so the
     * revoke lands on no row. The real id has been written to PatchDb by then,
     * which is what the deleted-message indicator reads.
     */
    private static final String UNFINDABLE_ID = "1234";

    /** The inbound message protobuf. Picks the builder's own fields by type. */
    static Class<?> message_class;

    /** Volatile so "once" holds however many threads the receive path uses. */
    private static volatile boolean loggedFirst;

    static void handle_view_once(Object obj) {
        try {
            for (Field field : obj.getClass().getDeclaredFields()) {
                Object value = field.get(obj);
                if (value == null) {
                    continue;
                }
                try {
                    Field view_once_field = value.getClass().getDeclaredField("viewOnce_");
                    view_once_field.setAccessible(true);
                    boolean is_view_once = (boolean) view_once_field.get(value);
                    if (is_view_once) {
                        view_once_field.set(value, false);
                    }
                } catch (NoSuchFieldException ignored) {
                } catch (Exception e) {
                    Log.e(TAG, "DecryptProtobuf: Error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "DecryptProtobuf: Error: " + e.getMessage());
        }
    }

    /**
     * The protocol message's key names the message being revoked. Jid and
     * fromMe are best-effort: a missing one must not cost us the id, which is
     * what the indicator actually looks up.
     */
    static void record_deleted(Object protocol_key, String id) {
        try {
            Class<?> key_class = protocol_key.getClass();
            String remote_jid = null;
            boolean from_me = false;
            try {
                Field jid_field = key_class.getDeclaredField("remoteJid_");
                jid_field.setAccessible(true);
                remote_jid = (String) jid_field.get(protocol_key);
                Field from_me_field = key_class.getDeclaredField("fromMe_");
                from_me_field.setAccessible(true);
                from_me = from_me_field.getBoolean(protocol_key);
            } catch (NoSuchFieldException ignored) {
            }
            PatchDb.markDeleted(new MessageKey(id, remote_jid, from_me), System.currentTimeMillis());
        } catch (Throwable t) {
            Log.e(TAG, "DecryptProtobuf: could not record deleted message", t);
        }
    }

    static void handle_delete_message(Object protocol_message) {
        try {
            Field key_field = protocol_message.getClass().getDeclaredField("key_");
            key_field.setAccessible(true);
            Object key = key_field.get(protocol_message);
            if (key == null) {
                return;
            }
            Field id_field = key.getClass().getDeclaredField("id_");
            id_field.setAccessible(true);
            String id = (String) id_field.get(key);
            // Already rewritten: the builder carries the same protobuf in both
            // of its message fields, and a second pass would file the revoke
            // under the sentinel instead of under the message it names.
            if (id == null || UNFINDABLE_ID.equals(id)) {
                return;
            }
            record_deleted(key, id);
            id_field.set(key, UNFINDABLE_ID);
        } catch (NoSuchFieldException e) {
            Log.e(TAG, "DecryptProtobuf: field error: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "DecryptProtobuf: Error: " + e.getMessage());
        }
    }

    static void handle_protocol_message(Object message) {
        try {
            Field protocol_message_field = message.getClass().getDeclaredField("protocolMessage_");
            protocol_message_field.setAccessible(true);
            Object protocol_message = protocol_message_field.get(message);
            if (protocol_message != null) {
                Field protocol_type = protocol_message.getClass().getDeclaredField("type_");
                protocol_type.setAccessible(true);
                Object type_object = protocol_type.get(protocol_message);
                if (type_object == null) {
                    return;
                }
                switch ((int) type_object) {
                    case 0:
                        handle_delete_message(protocol_message);
                        break;
                }
            }
        } catch (NoSuchFieldException e) {
            Log.i(TAG, "DecryptProtobuf: NoSuchFieldException: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "DecryptProtobuf: Error: " + e.getMessage());
        }
    }

    /**
     * Every message the builder holds, rewritten once each.
     *
     * The builder is handed the same protobuf twice -- once as the message and
     * once as the original it was derived from -- so the second field is
     * usually the first object again. {@link #handle_delete_message} would
     * survive a second pass on its own; skipping it keeps the common case to
     * one walk of a 200-field protobuf.
     */
    private static void rewrite(Object builder) throws IllegalAccessException {
        Object scrubbed = null;
        for (Field field : builder.getClass().getDeclaredFields()) {
            if (field.getType() != message_class) {
                continue;
            }
            field.setAccessible(true);
            Object message = field.get(builder);
            if (message == null || message == scrubbed) {
                continue;
            }
            scrubbed = message;
            handle_view_once(message);
            handle_protocol_message(message);
            if (!loggedFirst) {
                loggedFirst = true;
                Log.i(TAG, "DecryptProtobuf: first message rewritten, builder "
                        + builder.getClass().getName() + ", protobuf " + message.getClass().getName());
            }
        }
    }

    /** native on purpose: a body here would be inlined into the hook and the backup would
     * silently answer for the original. ArtHooks rewrites its entry point. */
    static native Object params_backup(Object thiz);

    static Object params_hook(Object thiz) {
        try {
            rewrite(thiz);
        } catch (Throwable t) {
            // A message is mid-delivery; a throw here would take it down and,
            // on the first launch after install, the app with it.
            Log.e(TAG, "DecryptProtobuf: could not rewrite a message: " + t);
        }
        return params_backup(thiz);
    }

    public String id() {
        return "decrypt_protobuf";
    }

    public String title() {
        return "Keep deleted and view-once messages";
    }

    public String description() {
        return "Deleted messages stay. View-once reopens.";
    }

    public HookCategory category() {
        return HookCategory.PRIVACY;
    }

    public void load() {
        try {
            message_class = Class.forName("{{DECRYPT_PROTOBUF_CLASS_NAME}}");
            Class<?> builder = Class.forName("{{PARSE_PARAMS_BUILDER_CLASS_NAME}}");

            Executable build = ArtHooks.find_function(builder,
                    "{{PARSE_PARAMS_BUILDER_METHOD_NAME}}", "{{PARSE_PARAMS_BUILDER_METHOD_SIG}}");
            if (build == null) {
                Log.e(TAG, "DecryptProtobuf: no {{PARSE_PARAMS_BUILDER_METHOD_NAME}} on " + builder.getName());
                return;
            }
            // R8 staticizes an instance method whose receiver goes unused, and a
            // static target's backup re-enters the replacement: StackOverflowError
            // on the first message, outside every try/catch, host app dead.
            if (Modifier.isStatic(build.getModifiers())) {
                Log.e(TAG, "DecryptProtobuf: " + builder.getName()
                        + ".{{PARSE_PARAMS_BUILDER_METHOD_NAME}} is static, declining to hook it");
                return;
            }
            Method replacement = DecryptProtobuf.class.getDeclaredMethod("params_hook", Object.class);
            Method original = DecryptProtobuf.class.getDeclaredMethod("params_backup", Object.class);
            boolean hooked = ArtHooks.hook_function(build, replacement, original);

            Log.i(TAG, "DecryptProtobuf: hooked " + builder.getName()
                    + ".{{PARSE_PARAMS_BUILDER_METHOD_NAME}}, message " + message_class.getName()
                    + ", hooked=" + hooked);
        } catch (Throwable t) {
            Log.e(TAG, "DecryptProtobuf: not loaded: " + t);
        }
    }

    public void unload() {
        Log.i(TAG, "DecryptProtobuf: Patch unloaded");
    }
}
