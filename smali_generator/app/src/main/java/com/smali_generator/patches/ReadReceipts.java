package com.smali_generator.patches;

import android.util.Log;

import com.arthooks.ArtHooks;

import com.smali_generator.Hook;
import com.smali_generator.HookCategory;
import com.smali_generator.db.PatchDb;
import com.smali_generator.ui.ChatPickerActivity;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Locale;


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
 * Both decisions run through the class its log strings call ReadReceiptUtils.
 * The read one has a method of its own, which is simply forced to the self
 * variant. The played one does not, and is the reason for the third hook
 * below.
 *
 * Doing it here rather than at the app's own privacy setting is what keeps the
 * asymmetry worth having: flipping that setting also stops *you* from seeing
 * other people's blue ticks, and this does not.
 *
 * The played gate covers the blue microphone on voice notes. It is included
 * because the two are one setting in WhatsApp's own UI, so leaving it out
 * would leak the very thing the hook is for -- grey ticks on a chat, and then
 * a blue mic the moment a voice note is opened.
 *
 * That gate is not a played-receipt method, though: it is the app's general
 * "are read receipts on for this chat", with twelve callers on 2.26.36.71.
 * One is the job that sends the played receipt; another is
 * MessageStatusUpdateReceiptFactory, which reads it when a receipt arrives
 * from the other side and, on a "no", rewrites the incoming read or played
 * status down to delivered -- WhatsApp's own reciprocity rule. Answering no
 * everywhere therefore turns that rule on the user and hides other people's
 * blue ticks, which is the one thing this hook promises not to do. So the
 * answer is scoped: {@link #played_job_hook} marks the job while it runs, and
 * the gate lies only to the single call made inside it.
 *
 * Which chats it applies to is a {@link Scope}, chosen on {@link
 * ChatPickerActivity}. Both hooked methods take the chat's Jid, so the scope
 * is consulted per receipt rather than per process -- unlike the switch that
 * installs the hook at all, changing the scope takes effect immediately.
 */
public class ReadReceipts implements Hook {

    private static final String TAG = "PATCH";

    /** The receipt that syncs to your own devices and goes nowhere else. */
    private static final String READ_SELF = "read-self";

    /** Key for this hook's chat list, and for its scope setting. */
    public static final String FEATURE = "read_receipts";
    private static final String SCOPE_KEY = "read_receipts_scope";

    /**
     * Which chats receipts are held back from.
     *
     * One list covers both directions people ask for: {@code ONLY_LISTED} is a
     * blocklist and {@code ALL_EXCEPT_LISTED} an allowlist over the same set of
     * chats, so switching between them keeps the picks.
     */
    public enum Scope {
        EVERYONE("Every chat", "Nobody sees your read receipts."),
        ONLY_LISTED("Only the chats I pick", "Hidden from the chats below. Everyone else sees them as usual."),
        ALL_EXCEPT_LISTED("Every chat except the ones I pick", "Hidden from everyone but the chats below.");

        private final String title;
        private final String caption;

        Scope(String title, String caption) {
            this.title = title;
            this.caption = caption;
        }

        public String title() {
            return title;
        }

        public String caption() {
            return caption;
        }
    }

    /** Resolved once at load; the Jid class is the host app's, so it is found by reflection. */
    private static volatile Method rawJidMethod;

    public static Scope scope() {
        String stored = PatchDb.getString(SCOPE_KEY, Scope.EVERYONE.name());
        try {
            return Scope.valueOf(stored);
        } catch (IllegalArgumentException e) {
            // A scope written by a newer build, or a corrupted row. Holding
            // everything back is the reading that cannot leak a receipt.
            Log.e(TAG, "ReadReceipts: unknown scope " + stored + ", falling back to every chat");
            return Scope.EVERYONE;
        }
    }

    public static void setScope(Scope scope) {
        PatchDb.setString(SCOPE_KEY, scope.name());
    }

    /** Volatile so "once" holds across the threads receipts are sent from. */
    private static volatile boolean logged_read;
    private static volatile boolean logged_played;

    /**
     * Set while this thread is inside the played-receipt job.
     *
     * The gate is shared, so this is what separates the one caller that is
     * deciding what to *send* from the many that are deciding what to *show*.
     */
    private static final ThreadLocal<Boolean> in_played_job = new ThreadLocal<>();

    /**
     * Why one chat's receipts were, or were not, held back.
     *
     * Carries no chat identifier, and neither does anything logged from it:
     * this runs for every message read, and a log line naming the chat would
     * be a record of who the user talks to and when -- the thing the hook
     * exists to keep from leaking.
     */
    private static final class Decision {
        final boolean suppress;
        private final Scope scope;
        private final String chat;

        private Decision(boolean suppress, Scope scope, String chat) {
            this.suppress = suppress;
            this.scope = scope;
            this.chat = chat;
        }

        /** Nothing about the chat was looked at, so the line must not claim otherwise. */
        static Decision everyChat(Scope scope) {
            return new Decision(true, scope, null);
        }

        static Decision unidentified(Scope scope) {
            return new Decision(true, scope, "chat unidentified");
        }

        static Decision forChat(Scope scope, boolean picked) {
            return new Decision(scope == Scope.ONLY_LISTED ? picked : !picked, scope,
                    picked ? "chat is picked" : "chat is not picked");
        }

        @Override
        public String toString() {
            return "scope=" + scope + (chat == null ? "" : ", " + chat)
                    + (suppress ? ", held back" : ", sent as usual");
        }
    }

    /**
     * Whether this chat's receipts should be held back.
     *
     * One decision for both receipt kinds. A chat that cannot be identified is
     * held back whatever the scope says: the two ways to be wrong are not
     * equal, since a receipt withheld by mistake is invisible and one sent by
     * mistake cannot be taken back.
     */
    private static Decision decide(Object jid) {
        Scope scope = scope();
        if (scope == Scope.EVERYONE) {
            return Decision.everyChat(scope);
        }
        String raw = raw_jid(jid);
        if (raw == null) {
            return Decision.unidentified(scope);
        }
        return Decision.forChat(scope, PatchDb.isChatSelected(FEATURE, raw));
    }

    /**
     * The chat's jid as text, which is what a selection is keyed on.
     *
     * Deliberately not toString(): that returns the Jid's obfuscated form,
     * which is what the app logs rather than what identifies a chat.
     */
    private static String raw_jid(Object jid) {
        Method method = rawJidMethod;
        if (method == null || jid == null) {
            return null;
        }
        try {
            Object raw = method.invoke(jid);
            return raw instanceof String ? (String) raw : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** native on purpose: a body here would be inlined into the hook and the backup would
     * silently answer for the original. ArtHooks rewrites its entry point. */
    static native String receipt_type_backup(Object thiz, Object jid, boolean force_read_self);

    static String receipt_type_hook(Object thiz, Object jid, boolean force_read_self) {
        Decision decision = decide(jid);
        // Always asked, even when the answer is discarded: it is what the app
        // would have done, so a chat that is not held back keeps WhatsApp's own
        // rules rather than a second implementation of them. Calling through is
        // safe and cheap -- the original only reads settings and chat state.
        String original = receipt_type_backup(thiz, jid, force_read_self);
        if (!logged_read) {
            logged_read = true;
            Log.i(TAG, "ReadReceipts: first read receipt: " + decision
                    + ", app wanted \"" + original + "\"");
        }
        return decision.suppress ? READ_SELF : original;
    }

    /** native on purpose: a body here would be inlined into the hook and the backup would
     * silently answer for the original. ArtHooks rewrites its entry point. */
    static native boolean played_gate_backup(Object thiz, Object jid);

    static boolean played_gate_hook(Object thiz, Object jid) {
        boolean original = played_gate_backup(thiz, jid);
        if (!Boolean.TRUE.equals(in_played_job.get())) {
            // Every other caller is asking so it can decide what to display,
            // and gets the app's own answer untouched. Note this returns
            // before decide(), so the shared path costs one ThreadLocal read.
            return original;
        }
        // Consumed rather than merely read: the job asks once, early, and
        // whatever it calls afterwards on this thread is no longer the send
        // decision. This narrows the lie to exactly that one question.
        in_played_job.remove();
        Decision decision = decide(jid);
        if (!logged_played) {
            logged_played = true;
            Log.i(TAG, "ReadReceipts: first played receipt: " + decision
                    + ", app wanted \"" + (original ? "played" : "played-self") + "\"");
        }
        // false picks "played-self", the voice-note counterpart of read-self.
        return decision.suppress ? false : original;
    }

    /** native on purpose: a body here would be inlined into the hook and the backup would
     * silently answer for the original. ArtHooks rewrites its entry point. */
    static native void played_job_backup(Object thiz);

    /**
     * Brackets one run of the played-receipt job, so the gate can tell that
     * run's question apart from the same question asked anywhere else.
     */
    static void played_job_hook(Object thiz) {
        in_played_job.set(Boolean.TRUE);
        try {
            played_job_backup(thiz);
        } finally {
            // Cleared even when the job throws -- it is allowed to, the queue
            // retries it. A flag left behind would follow the next piece of
            // work onto this pooled thread and suppress a receipt nobody asked
            // about, or worse, clamp an incoming one.
            in_played_job.remove();
        }
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

    public String configSummary() {
        Scope scope = scope();
        if (scope == Scope.EVERYONE) {
            return "Applies to every chat";
        }
        int count = PatchDb.selectedChats(FEATURE).size();
        String chats = count == 1 ? "1 chat" : count + " chats";
        return String.format(Locale.US, scope == Scope.ONLY_LISTED
                ? "Applies to %s" : "Applies to every chat except %s", chats);
    }

    public Class<?> configScreen() {
        return ChatPickerActivity.class;
    }

    public void load() {
        try {
            rawJidMethod = Class.forName("{{JID_CLASS_NAME}}")
                    .getMethod("{{JID_RAW_STRING_METHOD_NAME}}");
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

            Class<?> played_job = Class.forName("{{PLAYED_RECEIPT_JOB_CLASS_NAME}}");
            Executable played_run = ArtHooks.find_function(played_job,
                    "{{PLAYED_RECEIPT_JOB_METHOD_NAME}}", "{{PLAYED_RECEIPT_JOB_METHOD_SIG}}");
            Method played_job_replacement = ReadReceipts.class.getDeclaredMethod(
                    "played_job_hook", Object.class);
            Method played_job_original = ReadReceipts.class.getDeclaredMethod(
                    "played_job_backup", Object.class);
            boolean job_hooked = ArtHooks.hook_function(
                    played_run, played_job_replacement, played_job_original);

            // job= is worth reading: without it the gate never fires, so the
            // blue microphone leaks while everything else looks installed.
            Log.i(TAG, "ReadReceipts: Patch loaded on " + read_receipt_utils.getName()
                    + ", read=" + read_hooked + " played=" + played_hooked + " job=" + job_hooked
                    + ", scope=" + scope() + " chats=" + PatchDb.selectedChats(FEATURE).size());
        } catch (Throwable t) {
            Log.e(TAG, "ReadReceipts: Error: " + t);
        }
    }

    public void unload() {
        Log.i(TAG, "ReadReceipts: Patch unloaded");
    }
}
