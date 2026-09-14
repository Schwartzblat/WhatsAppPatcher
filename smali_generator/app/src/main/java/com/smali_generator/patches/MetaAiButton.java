package com.smali_generator.patches;

import android.util.Log;

import com.arthooks.ArtHooks;

import com.smali_generator.Hook;
import com.smali_generator.HookCategory;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;


/**
 * Takes Meta AI off the home screen.
 *
 * WhatsApp has put Meta AI on that screen two different ways, and which one a
 * build and an account get is not this patch's to choose, so it removes both:
 *
 * <ul>
 * <li>the <b>tab</b> in the bottom bar, between Updates and Calls. The bar is
 *     drawn from an ordered list of tab ids the app builds at startup, so a tab
 *     whose id is not in the list does not exist. Entries already come and go
 *     there per account -- Updates drops out under a privacy setting, the first
 *     slot is Meta AI or one of two other tabs depending on gating -- so taking
 *     one out is a shape the screen is built to take;</li>
 * <li>the <b>button</b> above the new-chat FAB, which appears as a round fab
 *     carrying the Meta AI ring or as an extended pill with text beside it. The
 *     screen asks one question before it draws either, and before it acts on a
 *     tap: is the Meta AI button on? That is a setting WhatsApp keeps in its own
 *     preferences, so answering no is a state it already has a name for. The
 *     calls tab shows the same button from the same setting but asks the
 *     question itself, with its own copy of the check, so it is answered
 *     separately.</li>
 * </ul>
 *
 * No half depends on another, and each is installed in its own try/catch: a
 * build that has only some of these entry points still loses them.
 *
 * Hooking the chat list's own "do you want the pill" gate instead was the
 * earlier mistake. It is only one of the four callers of the real gate, so the
 * round fab -- the form most accounts get -- was left in place, and on a device
 * that was never offered the pill the patch logged success and changed nothing.
 */
public class MetaAiButton implements Hook {

    private static final String TAG = "PATCH";

    /**
     * The Meta AI tab's id, resolved at load.
     *
     * Left at NO_TAB when the artifact did not resolve, which is also the only
     * path on which the list is not hooked at all -- so the value is never used
     * to remove a tab that was not identified.
     */
    private static final int NO_TAB = -1;
    private static volatile int metaAiTab = NO_TAB;

    /** Volatile so "once" holds however the home screen is rebuilt. */
    private static volatile boolean loggedTabs;
    private static volatile boolean loggedButton;
    private static volatile boolean loggedCalls;

    /** native on purpose: a body here would be inlined into the hook and the backup would
     * silently answer for the original. ArtHooks rewrites its entry point. */
    static native ArrayList tabs_backup(Object thiz);

    static ArrayList tabs_hook(Object thiz) {
        ArrayList tabs = tabs_backup(thiz);
        try {
            // The app's own list, minus one entry: everything downstream --
            // which tab is selected, what the bar draws, what the pager holds --
            // is derived from it, so there is nothing else to keep in step.
            boolean removed = tabs != null && tabs.remove(Integer.valueOf(metaAiTab));
            if (!loggedTabs) {
                loggedTabs = true;
                Log.i(TAG, "MetaAiButton: home tabs " + tabs + (removed
                        ? ", Meta AI removed" : ", no Meta AI tab to remove"));
            }
        } catch (Throwable t) {
            // The home screen is being built; a throw here would take it down.
            if (!loggedTabs) {
                loggedTabs = true;
                Log.e(TAG, "MetaAiButton: could not edit the tab list: " + t);
            }
        }
        return tabs;
    }

    /** native on purpose: a body here would be inlined into the hook and the backup would
     * silently answer for the original. ArtHooks rewrites its entry point. */
    static native boolean button_gate_backup(Object thiz);

    static boolean button_gate_hook(Object thiz) {
        if (!loggedButton) {
            loggedButton = true;
            Log.i(TAG, "MetaAiButton: the Meta AI button is off on the chats screen"
                    + "; WhatsApp would have shown it: " + wanted(thiz, true));
        }
        return false;
    }

    /** native on purpose: a body here would be inlined into the hook and the backup would
     * silently answer for the original. ArtHooks rewrites its entry point. */
    static native boolean calls_gate_backup(Object thiz);

    static boolean calls_gate_hook(Object thiz) {
        if (!loggedCalls) {
            loggedCalls = true;
            Log.i(TAG, "MetaAiButton: the Meta AI button is off on the calls screen"
                    + "; WhatsApp would have shown it: " + wanted(thiz, false));
        }
        return false;
    }

    /**
     * What WhatsApp's own gate would have said, for the log line only.
     *
     * It is the difference between "the button was taken away" and "this
     * account was never offered one", which is otherwise indistinguishable from
     * outside -- and telling those apart is the whole reason this hook was wrong
     * the first time. Safe to ask: both originals only read a setting and a
     * gating flag. Guarded anyway, since a throw here would land in a call
     * WhatsApp makes while building the screen.
     */
    private static String wanted(Object thiz, boolean chats) {
        try {
            return String.valueOf(chats ? button_gate_backup(thiz) : calls_gate_backup(thiz));
        } catch (Throwable t) {
            return "unknown (" + t + ")";
        }
    }

    public String id() {
        return "meta_ai_button";
    }

    public String title() {
        return "Remove Meta AI from the home screen";
    }

    public String description() {
        return "Takes the Meta AI tab out of the bottom bar, and the Meta AI button off the chats and calls screens.";
    }

    public HookCategory category() {
        return HookCategory.INTERFACE;
    }

    public void load() {
        remove_tab();
        remove_button();
        remove_calls_button();
    }

    private void remove_tab() {
        try {
            // Parsed rather than inlined as a literal: an unsubstituted
            // placeholder then fails here, before anything is hooked, instead of
            // becoming a tab id nothing matches.
            metaAiTab = Integer.parseInt("{{META_AI_TAB_ID}}");
            Class<?> tabs = Class.forName("{{META_AI_TABS_CLASS_NAME}}");

            Executable builder = ArtHooks.find_function(tabs,
                    "{{META_AI_TABS_METHOD_NAME}}", "{{META_AI_TABS_METHOD_SIG}}");
            Method replacement = MetaAiButton.class.getDeclaredMethod("tabs_hook", Object.class);
            Method original = MetaAiButton.class.getDeclaredMethod("tabs_backup", Object.class);
            boolean hooked = ArtHooks.hook_function(builder, replacement, original);

            Log.i(TAG, "MetaAiButton: tab " + metaAiTab + " hooked on " + tabs.getName()
                    + ".{{META_AI_TABS_METHOD_NAME}}, hooked=" + hooked);
        } catch (Throwable t) {
            Log.e(TAG, "MetaAiButton: the Meta AI tab was left in place: " + t);
        }
    }

    private void remove_button() {
        try {
            Class<?> owner = Class.forName("{{META_AI_BUTTON_GATE_CLASS_NAME}}");

            Executable gate = ArtHooks.find_function(owner,
                    "{{META_AI_BUTTON_GATE_METHOD_NAME}}", "{{META_AI_BUTTON_GATE_METHOD_SIG}}");
            Method replacement = MetaAiButton.class.getDeclaredMethod("button_gate_hook", Object.class);
            Method original = MetaAiButton.class.getDeclaredMethod("button_gate_backup", Object.class);
            boolean hooked = ArtHooks.hook_function(gate, replacement, original);

            Log.i(TAG, "MetaAiButton: button gate hooked on " + owner.getName()
                    + ".{{META_AI_BUTTON_GATE_METHOD_NAME}}, hooked=" + hooked);
        } catch (Throwable t) {
            Log.e(TAG, "MetaAiButton: the Meta AI button was left on the chats screen: " + t);
        }
    }

    private void remove_calls_button() {
        try {
            Class<?> calls = Class.forName("{{META_AI_CALLS_GATE_CLASS_NAME}}");

            Executable gate = ArtHooks.find_function(calls,
                    "{{META_AI_CALLS_GATE_METHOD_NAME}}", "{{META_AI_CALLS_GATE_METHOD_SIG}}");
            Method replacement = MetaAiButton.class.getDeclaredMethod("calls_gate_hook", Object.class);
            Method original = MetaAiButton.class.getDeclaredMethod("calls_gate_backup", Object.class);
            boolean hooked = ArtHooks.hook_function(gate, replacement, original);

            Log.i(TAG, "MetaAiButton: calls gate hooked on " + calls.getName()
                    + ".{{META_AI_CALLS_GATE_METHOD_NAME}}, hooked=" + hooked);
        } catch (Throwable t) {
            Log.e(TAG, "MetaAiButton: the Meta AI button was left on the calls screen: " + t);
        }
    }

    public void unload() {
        Log.i(TAG, "MetaAiButton: Patch unloaded");
    }
}
