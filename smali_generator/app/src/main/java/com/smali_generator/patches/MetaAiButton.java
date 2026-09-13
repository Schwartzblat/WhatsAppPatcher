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
 * <li>the <b>button</b> above the new-chat FAB on the chats tab, which the
 *     app keeps in a ViewStub and inflates only after asking the chat list
 *     whether it wants one. Every other tab answers a constant no and never
 *     gets a button, and so does an account Meta AI was never offered to; this
 *     makes the chat list answer the same way. The stub is then never inflated,
 *     so the button is absent rather than hidden.</li>
 * </ul>
 *
 * Neither half depends on the other, and each is installed in its own
 * try/catch: a build that has only one of the two entry points still loses it.
 *
 * The chat list's gate is declared on the fragment the other chat lists --
 * archived, locked, the folder views -- inherit from without overriding, so one
 * hook keeps the button off all of them.
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
    private static volatile boolean loggedFab;

    /** Empty on purpose: ArtHooks rewrites its entry point to the original. */
    static ArrayList tabs_backup(Object thiz) {
        return null;
    }

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

    /** Empty on purpose: ArtHooks rewrites its entry point to the original. */
    static boolean fab_gate_backup(Object thiz) {
        return false;
    }

    static boolean fab_gate_hook(Object thiz) {
        if (!loggedFab) {
            loggedFab = true;
            Log.i(TAG, "MetaAiButton: the Meta AI button is blocked on the chats screen"
                    + "; WhatsApp would have shown it: " + wanted(thiz));
        }
        return false;
    }

    /**
     * What WhatsApp's own gate would have said, for the log line only.
     *
     * It is the difference between "the button was taken away" and "this
     * account was never offered one", which is otherwise indistinguishable from
     * outside. Safe to ask -- the original only reads settings and gating flags,
     * which is the property the finder pins it on -- but guarded anyway, since a
     * throw here would land in a call WhatsApp makes while building the screen.
     */
    private static String wanted(Object thiz) {
        try {
            return String.valueOf(fab_gate_backup(thiz));
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
        return "Takes the Meta AI tab out of the bottom bar, and the Meta AI button off the chats screen.";
    }

    public HookCategory category() {
        return HookCategory.INTERFACE;
    }

    public void load() {
        remove_tab();
        remove_fab();
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

    private void remove_fab() {
        try {
            Class<?> chat_list = Class.forName("{{META_AI_FAB_GATE_CLASS_NAME}}");

            Executable gate = ArtHooks.find_function(chat_list,
                    "{{META_AI_FAB_GATE_METHOD_NAME}}", "{{META_AI_FAB_GATE_METHOD_SIG}}");
            Method replacement = MetaAiButton.class.getDeclaredMethod("fab_gate_hook", Object.class);
            Method original = MetaAiButton.class.getDeclaredMethod("fab_gate_backup", Object.class);
            boolean hooked = ArtHooks.hook_function(gate, replacement, original);

            Log.i(TAG, "MetaAiButton: fab gate hooked on " + chat_list.getName()
                    + ".{{META_AI_FAB_GATE_METHOD_NAME}}, hooked=" + hooked);
        } catch (Throwable t) {
            Log.e(TAG, "MetaAiButton: the Meta AI button was left in place: " + t);
        }
    }

    public void unload() {
        Log.i(TAG, "MetaAiButton: Patch unloaded");
    }
}
