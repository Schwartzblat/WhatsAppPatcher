package com.smali_generator.patches;

import android.util.Log;

import com.arthooks.ArtHooks;

import com.smali_generator.Hook;
import com.smali_generator.HookCategory;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Offers "Delete for everyone" on messages WhatsApp thinks are too old.
 *
 * WhatsApp decides this once, in the builder of the "Delete message?" dialog:
 * for each selected message it asks whether {@code timestamp + 216000000} --
 * two days and twelve hours -- is still ahead of the server-corrected clock,
 * and only adds the "Delete for everyone" button when every other condition
 * holds <i>and</i> that one does. The window is a literal compiled into that
 * method, not an A/B property, so there is nothing to override: the number
 * appears exactly once in the whole APK and no server value reaches it.
 *
 * Nor is there a smaller method to hook. The comparison is inlined into a
 * five-hundred-instruction method, and the two things it reads are a field of
 * the message and a static time helper -- neither of them hookable, the helper
 * because ArtHooks cannot hook a static and because every clock in the app
 * comes out of it.
 *
 * So the hook changes what the builder reads. Around the call to the original
 * it moves each selected message's timestamp forward to now and puts it back
 * afterwards, which makes the age test pass and leaves every other test in
 * that method exactly as it was: a message the app would refuse to unsend for
 * any reason other than its age is still refused. The window is never named
 * here, so a release that changes it changes nothing.
 *
 * <b>Two properties of the edit it does not share.</b> The value is restored in
 * a {@code finally}, and the builder is synchronous on the main thread, so the
 * altered timestamp is visible for the few milliseconds the dialog takes to
 * build -- a row bound on another thread in that window would draw the wrong
 * time for one frame. Nothing in the builder writes the message back: it reads
 * the field once, for this comparison, and the listeners it installs capture
 * the messages themselves, not their timestamps, and run long after the value
 * is back.
 *
 * <b>What it cannot do.</b> Once the button is tapped the revoke goes out
 * through the app's own path, which has no age check of its own for ordinary
 * chats. Whether the server still relays a revoke this old, and whether the
 * recipient's client applies it, is not decided on this device.
 */
public class DeleteForEveryone implements Hook {

    private static final String TAG = "PATCH";

    /**
     * The message's timestamp, in milliseconds. Which field it is is renamed
     * every release; the finder reads it out of the comparison itself.
     */
    private static volatile Field timestamp;

    /** native on purpose: a body here would be inlined into the hook and the backup would
     *  silently answer for the original. ArtHooks rewrites its entry point. */
    static native Object dialog_backup(Object thiz, Object context, Object a, Object b, Object c,
                                       Object title, Object messages, boolean revokable);

    static Object dialog_hook(Object thiz, Object context, Object a, Object b, Object c,
                              Object title, Object messages, boolean revokable) {
        Map<Object, Long> aged = ageForward(messages);
        try {
            return dialog_backup(thiz, context, a, b, c, title, messages, revokable);
        } finally {
            restore(aged);
        }
    }

    /**
     * Makes every selected message look as if it had just been sent, and
     * answers with what each one really said.
     *
     * Null on any failure, which leaves the dialog exactly as WhatsApp would
     * have built it -- this runs while the user is waiting for that dialog,
     * and there is no answer here worth taking the app down for.
     */
    private static Map<Object, Long> ageForward(Object messages) {
        Field when = timestamp;
        if (when == null || !(messages instanceof Collection)) {
            return null;
        }
        Map<Object, Long> aged = new IdentityHashMap<>();
        try {
            long now = System.currentTimeMillis();
            for (Object message : (Collection<?>) messages) {
                if (message == null || !when.getDeclaringClass().isInstance(message)) {
                    continue;
                }
                long sent = when.getLong(message);
                // A message the clock already agrees is recent needs nothing,
                // and one dated in the future must not be moved backwards.
                if (sent >= now) {
                    continue;
                }
                aged.put(message, sent);
                when.setLong(message, now);
            }
        } catch (Throwable t) {
            // Whatever was moved is moved; putting it back is the caller's job
            // either way, so hand back what has been collected so far.
            Log.e(TAG, "DeleteForEveryone: the selection was left as it was: " + t);
        }
        if (aged.isEmpty()) {
            return null;
        }
        Log.i(TAG, "DeleteForEveryone: " + aged.size() + " message(s) offered for everyone");
        return aged;
    }

    private static void restore(Map<Object, Long> aged) {
        if (aged == null) {
            return;
        }
        Field when = timestamp;
        for (Map.Entry<Object, Long> entry : aged.entrySet()) {
            try {
                when.setLong(entry.getKey(), entry.getValue());
            } catch (Throwable t) {
                // One message keeping a wrong timestamp for the rest of the
                // process is bad, but it is the message list's problem; giving
                // up on the rest of the selection would be worse.
                Log.e(TAG, "DeleteForEveryone: a message kept the timestamp it was given: " + t);
            }
        }
    }

    public String id() {
        return "delete_for_everyone";
    }

    public String title() {
        return "Delete for everyone, any time";
    }

    public String description() {
        return "Offers Delete for everyone on messages the app considers too old to unsend.";
    }

    public HookCategory category() {
        return HookCategory.MESSAGES;
    }

    public void load() {
        try {
            // Resolved before anything is hooked: without the field the
            // replacement has nothing to change, and a dialog builder hooked to
            // hand back its own answer is pure overhead on a user's tap.
            Class<?> message = Class.forName("{{MESSAGE_TIMESTAMP_CLASS_NAME}}");
            Field sentAt = message.getDeclaredField("{{MESSAGE_TIMESTAMP_FIELD_NAME}}");
            if (sentAt.getType() != long.class) {
                Log.e(TAG, "DeleteForEveryone: " + message.getName()
                        + ".{{MESSAGE_TIMESTAMP_FIELD_NAME}} is a " + sentAt.getType()
                        + ", not a timestamp; old messages stay undeletable");
                return;
            }
            sentAt.setAccessible(true);

            Class<?> owner = Class.forName("{{REVOKE_DIALOG_CLASS_NAME}}");
            Executable builder = ArtHooks.find_function(owner,
                    "{{REVOKE_DIALOG_METHOD_NAME}}", "{{REVOKE_DIALOG_METHOD_SIG}}");
            if (builder == null) {
                Log.e(TAG, "DeleteForEveryone: " + owner.getName()
                        + " has no dialog builder of the shape the finder saw");
                return;
            }
            // R8 makes an instance method static when its receiver goes unused,
            // without touching the descriptor the finder matched. The
            // replacement's leading parameter is the receiver, so a staticized
            // builder would read every argument one slot out, and the backup
            // would re-enter the replacement rather than build the dialog.
            if (Modifier.isStatic(builder.getModifiers())) {
                Log.e(TAG, "DeleteForEveryone: the dialog builder is static in this build,"
                        + " old messages stay undeletable");
                return;
            }
            Method replacement = DeleteForEveryone.class.getDeclaredMethod("dialog_hook",
                    Object.class, Object.class, Object.class, Object.class, Object.class,
                    Object.class, Object.class, boolean.class);
            Method original = DeleteForEveryone.class.getDeclaredMethod("dialog_backup",
                    Object.class, Object.class, Object.class, Object.class, Object.class,
                    Object.class, Object.class, boolean.class);
            boolean hooked = ArtHooks.hook_function(builder, replacement, original);
            if (!hooked) {
                // false means the builder runs unmodified with no exception
                // anywhere -- the same silence as success.
                Log.e(TAG, "DeleteForEveryone: hook_function returned false,"
                        + " old messages stay undeletable");
                return;
            }
            timestamp = sentAt;

            Log.i(TAG, "DeleteForEveryone: hooked on " + owner.getName()
                    + ".{{REVOKE_DIALOG_METHOD_NAME}}, timestamp " + message.getName()
                    + ".{{MESSAGE_TIMESTAMP_FIELD_NAME}}, hooked=" + hooked);
        } catch (Throwable t) {
            timestamp = null;
            Log.e(TAG, "DeleteForEveryone: old messages stay undeletable: " + t);
        }
    }

    public void unload() {
        Log.i(TAG, "DeleteForEveryone: Patch unloaded");
    }
}
