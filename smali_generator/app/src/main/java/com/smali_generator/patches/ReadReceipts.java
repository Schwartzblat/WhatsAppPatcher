package com.smali_generator.patches;

import android.util.Log;

import com.arthooks.ArtHooks;

import com.smali_generator.Hook;
import com.smali_generator.HookCategory;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;


/**
 * Stops read receipts from reaching the sender.
 *
 * WhatsApp never decides whether to send a receipt, only which kind: a
 * {@code read} receipt goes to the sender and turns their ticks blue, while a
 * {@code read-self} receipt goes only to your own linked devices, so they mark
 * the chat read without telling anyone. Voice notes have the same pair,
 * {@code played} and {@code played-self}. The app already sends the self
 * variants -- for your own status views, for chats it considers spam, and for
 * everyone when its own Read receipts setting is off -- so this hook does not
 * invent a state: it takes a path the app takes for itself.
 *
 * That is the whole patch. Both decisions are made in the class its log
 * strings call ReadReceiptUtils, one method per receipt kind, and both are
 * forced to the self variant.
 *
 * Doing it here rather than at the app's own privacy setting is what keeps the
 * asymmetry worth having: flipping that setting also stops *you* from seeing
 * other people's blue ticks, and this does not.
 *
 * The played gate covers the blue microphone on voice notes. It is included
 * because the two are one setting in WhatsApp's own UI, so leaving it out
 * would leak the very thing the hook is for -- grey ticks on a chat, and then
 * a blue mic the moment a voice note is opened.
 */
public class ReadReceipts implements Hook {

    private static final String TAG = "PATCH";

    /** The receipt that syncs to your own devices and goes nowhere else. */
    private static final String READ_SELF = "read-self";

    /** Volatile so "once" holds across the threads receipts are sent from. */
    private static volatile boolean logged_read;
    private static volatile boolean logged_played;

    /**
     * Whether this chat's receipts should be held back.
     *
     * One decision for both receipt kinds, taking the chat's Jid, which is
     * where a per-chat exception list plugs in. Today the answer is the same
     * for every chat: either the hook is installed and no receipt goes out, or
     * it is switched off and none of this runs.
     */
    private static boolean should_suppress(Object jid) {
        return true;
    }

    /** Empty on purpose: ArtHooks rewrites its entry point to the original. */
    static String receipt_type_backup(Object thiz, Object jid, boolean force_read_self) {
        return null;
    }

    static String receipt_type_hook(Object thiz, Object jid, boolean force_read_self) {
        if (!should_suppress(jid)) {
            return receipt_type_backup(thiz, jid, force_read_self);
        }
        if (!logged_read) {
            logged_read = true;
            // What the app would have sent, so the log says whether the hook is
            // merely running or actually changing the answer. Calling through is
            // safe: the original only reads settings and chat state.
            Log.i(TAG, "ReadReceipts: holding back read receipts, app wanted \""
                    + receipt_type_backup(thiz, jid, force_read_self) + "\"");
        }
        return READ_SELF;
    }

    /** Empty on purpose: ArtHooks rewrites its entry point to the original. */
    static boolean played_gate_backup(Object thiz, Object jid) {
        return false;
    }

    static boolean played_gate_hook(Object thiz, Object jid) {
        if (!should_suppress(jid)) {
            return played_gate_backup(thiz, jid);
        }
        if (!logged_played) {
            logged_played = true;
            Log.i(TAG, "ReadReceipts: holding back played receipts, app wanted \""
                    + (played_gate_backup(thiz, jid) ? "played" : "played-self") + "\"");
        }
        // false picks "played-self", the voice-note counterpart of read-self.
        return false;
    }

    public String id() {
        return "read_receipts";
    }

    public String title() {
        return "Hide when you've read a message";
    }

    public String description() {
        return "Nobody sees your blue ticks, or the blue microphone on voice notes. You still see theirs.";
    }

    public HookCategory category() {
        return HookCategory.PRIVACY;
    }

    public void load() {
        try {
            Class<?> read_receipt_utils = Class.forName("{{READ_RECEIPT_UTILS_CLASS_NAME}}");

            Executable receipt_type = ArtHooks.find_function(read_receipt_utils,
                    "{{RECEIPT_TYPE_METHOD_NAME}}", "{{RECEIPT_TYPE_METHOD_SIG}}");
            Method receipt_type_replacement = ReadReceipts.class.getDeclaredMethod(
                    "receipt_type_hook", Object.class, Object.class, boolean.class);
            Method receipt_type_original = ReadReceipts.class.getDeclaredMethod(
                    "receipt_type_backup", Object.class, Object.class, boolean.class);
            boolean read_hooked = ArtHooks.hook_function(
                    receipt_type, receipt_type_replacement, receipt_type_original);

            Executable played_gate = ArtHooks.find_function(read_receipt_utils,
                    "{{PLAYED_RECEIPT_GATE_METHOD_NAME}}", "{{PLAYED_RECEIPT_GATE_METHOD_SIG}}");
            Method played_gate_replacement = ReadReceipts.class.getDeclaredMethod(
                    "played_gate_hook", Object.class, Object.class);
            Method played_gate_original = ReadReceipts.class.getDeclaredMethod(
                    "played_gate_backup", Object.class, Object.class);
            boolean played_hooked = ArtHooks.hook_function(
                    played_gate, played_gate_replacement, played_gate_original);

            Log.i(TAG, "ReadReceipts: Patch loaded on " + read_receipt_utils.getName()
                    + ", read=" + read_hooked + " played=" + played_hooked);
        } catch (Throwable t) {
            Log.e(TAG, "ReadReceipts: Error: " + t);
        }
    }

    public void unload() {
        Log.i(TAG, "ReadReceipts: Patch unloaded");
    }
}
