package com.smali_generator.patches;

import android.util.Log;

import com.arthooks.ArtHooks;

import com.smali_generator.Hook;
import com.smali_generator.HookCategory;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;

/**
 * Lets any number of chats be pinned.
 *
 * WhatsApp's cap is {@code isBenefitActive(PINNED_CHATS) ? 20 : 3} -- three, or
 * twenty for a paid "WhatsApp Plus" subscriber -- and both numbers are literals
 * compiled into every place that enforces them. There are three, and none of
 * them can be changed by answering one question differently, so each is taken
 * on its own:
 *
 * <ul>
 * <li><b>The gate.</b> Every way of pinning a chat ends in one lambda,
 *     {@code ConversationPinHelper.pinSelectedJids}, which counts the pinned
 *     chats, refuses past the cap, and otherwise pins each new chat through the
 *     app's own primitive -- the one that writes the database and queues the
 *     mutation that syncs it to linked devices. This hook does that last part
 *     itself: the chats the selection adds, stamped with WhatsApp's own clock,
 *     then the callback that ends the selection mode, then the app's own toast.
 *     Nothing is pinned any other way than WhatsApp would pin it.</li>
 * <li><b>The trim.</b> Every time the Chats tab is shown, WhatsApp unpins
 *     everything past the cap. Left alone it would undo the gate the next time
 *     the user looked at their chats, so it is not run at all.</li>
 * <li><b>The benefit.</b> The third enforcement point is the handler that
 *     reconciles pins synced from a linked device, which keeps only the newest
 *     N. It is static, its unpin is small enough to be inlined into it, and it
 *     also performs the unpins that are not about the cap -- a chat unpinned on
 *     WhatsApp Web -- so it cannot be taken out. Answering yes for the
 *     PINNED_CHATS benefit makes its N 20 instead of 3. It also keeps the
 *     subscription upsell from replacing a pin once three are taken.</li>
 * </ul>
 *
 * <b>What it cannot do.</b> A pin or unpin made on a linked device, or linking a
 * new one, still runs that reconcile, and it unpins all but the twenty most
 * recently pinned chats on this phone.
 *
 * The gate and the trim are not methods of their own: R8 merged each into one
 * of the release's {@code $t}-switched lambda classes, so the hook is on that
 * class's {@code run()} and answers for one case of it, passing every other
 * lambda the class carries straight through.
 */
public class PinnedChats implements Hook {

    private static final String TAG = "PATCH";

    /** The benefit constant, compared by identity; null until resolved. */
    private static volatile Object pinnedChats;

    private static volatile Lambda trim;
    private static volatile Gate gate;

    /** Volatile so the trim is reported once however many threads post it. */
    private static volatile boolean trimReported;

    /** native on purpose: a body here would be inlined into the hook and the backup would
     *  silently answer for the original. ArtHooks rewrites its entry point. */
    static native boolean benefit_backup(Object thiz, Object benefit);

    static boolean benefit_hook(Object thiz, Object benefit) {
        if (benefit != null && benefit == pinnedChats) {
            return true;
        }
        return benefit_backup(thiz, benefit);
    }

    static native void trim_backup(Object thiz);

    static void trim_hook(Object thiz) {
        if (!handled(thiz)) {
            trim_backup(thiz);
        }
    }

    static native void gate_backup(Object thiz);

    static void gate_hook(Object thiz) {
        if (!handled(thiz)) {
            gate_backup(thiz);
        }
    }

    /**
     * True when this lambda was the trim or the gate and has been dealt with.
     *
     * Both replacements ask both questions. R8 has so far put the two in
     * different classes, but if a release merges them only one redirect can be
     * installed -- a second would land on the first's replacement -- and that
     * one then has to serve both.
     */
    private static boolean handled(Object thiz) {
        Lambda skip = trim;
        if (skip != null && skip.handles(thiz)) {
            if (!trimReported) {
                trimReported = true;
                Log.i(TAG, "PinnedChats: skipped the trim that unpins chats past the cap");
            }
            return true;
        }
        Gate pin = gate;
        return pin != null && pin.handles(thiz) && pin.pin(thiz);
    }

    /** One case of a merged lambda class, recognised by the value of its switch field. */
    private static class Lambda {
        final Class<?> owner;
        final Field caseField;
        final int lambda;

        Lambda(Class<?> owner, String caseField, String lambda) throws ReflectiveOperationException {
            this.owner = owner;
            this.caseField = field(owner, caseField);
            if (this.caseField.getType() != int.class) {
                throw new NoSuchFieldException(owner.getName() + "." + caseField + " is not the switch");
            }
            // Parsed rather than inlined as a literal: an unsubstituted
            // placeholder then fails at load instead of failing to compile.
            this.lambda = Integer.parseInt(lambda);
        }

        boolean handles(Object thiz) {
            try {
                return owner.isInstance(thiz) && caseField.getInt(thiz) == lambda;
            } catch (Throwable t) {
                return false;
            }
        }
    }

    /**
     * Everything {@code pinSelectedJids} touches after its comparison, resolved
     * once. Each name was read by the finder out of that one case block.
     */
    private static final class Gate extends Lambda {
        final Field helper;
        final Field selection;
        final Field done;
        final Field storeHolder;
        final Method storeGetter;
        final Method pinnedJids;
        final Field pinner;
        final Method pinChat;
        final Field clock;
        final Method now;
        final Field toaster;
        final Method toast;
        final int pinnedOne;
        final int pinnedMany;
        final Method invoke;

        Gate(Class<?> owner) throws ReflectiveOperationException {
            super(owner, "{{PIN_GATE_CASE_FIELD}}", "{{PIN_GATE_CASE}}");
            helper = field(owner, "{{PIN_GATE_HELPER_FIELD}}");
            selection = field(owner, "{{PIN_GATE_SELECTION_FIELD}}");
            done = field(owner, "{{PIN_GATE_DONE_FIELD}}");

            Class<?> helperClass = Class.forName("{{PIN_HELPER_CLASS_NAME}}");
            storeHolder = field(helperClass, "{{PIN_HELPER_STORE_FIELD}}");
            pinner = field(helperClass, "{{PIN_HELPER_PINNER_FIELD}}");
            clock = field(helperClass, "{{PIN_HELPER_CLOCK_FIELD}}");
            toaster = field(helperClass, "{{PIN_HELPER_TOAST_FIELD}}");

            storeGetter = method(Class.forName("{{PIN_STORE_GETTER_CLASS_NAME}}"),
                    "{{PIN_STORE_GETTER_METHOD_NAME}}", "{{PIN_STORE_GETTER_METHOD_SIG}}", true);
            pinnedJids = method(storeGetter.getReturnType(),
                    "{{PIN_PINNED_METHOD_NAME}}", "{{PIN_PINNED_METHOD_SIG}}", true);
            pinChat = method(pinner.getType(),
                    "{{PIN_PINNER_METHOD_NAME}}", "{{PIN_PINNER_METHOD_SIG}}", false);
            now = method(clock.getType(), "{{PIN_CLOCK_METHOD_NAME}}", "{{PIN_CLOCK_METHOD_SIG}}", true);
            toast = method(toaster.getType(), "{{PIN_TOAST_METHOD_NAME}}", "(II)V", false);
            pinnedOne = Integer.decode("{{PIN_TOAST_ONE}}");
            pinnedMany = Integer.decode("{{PIN_TOAST_MANY}}");
            invoke = Class.forName("kotlin.jvm.functions.Function0").getMethod("invoke");
        }

        /**
         * Pins what the selection adds, with no cap.
         *
         * False when nothing has been pinned yet and WhatsApp's own gate should
         * run instead; that is also the answer for a pin that fails part-way,
         * since the gate pins whatever is still unpinned and refuses only past
         * its cap -- the worst case is the app's own "you can only pin" message,
         * never a lost pin. Runs on the background executor the app posted the
         * lambda to, as the original does.
         */
        boolean pin(Object thiz) {
            int pinned = 0;
            Object owner = null;
            try {
                owner = helper.get(thiz);
                Set<?> selected = (Set<?>) selection.get(thiz);
                // The whole map, newsletters included: `true` is what the gate
                // passes, and it counts channels against a separate cap of two.
                Object store = storeGetter.invoke(null, storeHolder.get(owner));
                Map<?, ?> already = (Map<?, ?>) pinnedJids.invoke(null, store, false);
                Object chats = pinner.get(owner);
                Object time = clock.get(owner);
                for (Object jid : selected) {
                    // Pinning a pinned chat again moves it to the top, which
                    // the original never does: it pins only what is new.
                    if (jid == null || already.containsKey(jid)) {
                        continue;
                    }
                    pinChat.invoke(chats, jid, (long) now.invoke(null, time));
                    pinned++;
                }
            } catch (Throwable t) {
                Log.e(TAG, "PinnedChats: handing the pin back to WhatsApp after " + pinned
                        + " chat(s): " + t);
                return false;
            }
            try {
                Object callback = done.get(thiz);
                if (callback != null) {
                    invoke.invoke(callback);
                }
                if (pinned > 0) {
                    toast.invoke(toaster.get(owner), pinned == 1 ? pinnedOne : pinnedMany, 0);
                }
            } catch (Throwable t) {
                // The pins are written; running the gate now would only add
                // its refusal on top of them.
                Log.e(TAG, "PinnedChats: pinned " + pinned + " chat(s) but the screen was not told: " + t);
            }
            Log.i(TAG, "PinnedChats: pinned " + pinned + " chat(s)");
            return true;
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field found = owner.getDeclaredField(name);
        found.setAccessible(true);
        return found;
    }

    /**
     * A method by its smali descriptor, statics included.
     *
     * The gate calls three static helpers, and these are called rather than
     * hooked, so all that matters is picking the overload the finder read.
     */
    private static Method method(Class<?> owner, String name, String sig, boolean isStatic)
            throws NoSuchMethodException {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            for (Method candidate : c.getDeclaredMethods()) {
                if (candidate.getName().equals(name)
                        && Modifier.isStatic(candidate.getModifiers()) == isStatic
                        && descriptor(candidate).equals(sig)) {
                    candidate.setAccessible(true);
                    return candidate;
                }
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + sig);
    }

    private static String descriptor(Method method) {
        StringBuilder out = new StringBuilder("(");
        for (Class<?> parameter : method.getParameterTypes()) {
            out.append(descriptor(parameter));
        }
        return out.append(')').append(descriptor(method.getReturnType())).toString();
    }

    private static String descriptor(Class<?> type) {
        if (type.isArray()) {
            return type.getName().replace('.', '/');
        }
        if (!type.isPrimitive()) {
            return "L" + type.getName().replace('.', '/') + ";";
        }
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        return "V";
    }

    public String id() {
        return "unlimited_pinned_chats";
    }

    public String title() {
        return "Unlimited pinned chats";
    }

    public String description() {
        return "Pin as many chats as you like. A pin changed on a linked device"
                + " still trims this phone to its 20 newest.";
    }

    public HookCategory category() {
        return HookCategory.UNLOCKS;
    }

    public void load() {
        unlock_benefit();
        Executable trimmed = skip_trim();
        // Without the trim out of the way every pin past the cap would be undone
        // the next time the Chats tab is shown, which is worse than the refusal.
        if (trimmed != null) {
            bypass_gate(trimmed);
        }
    }

    private void unlock_benefit() {
        try {
            Class<?> owner = Class.forName("{{PIN_BENEFIT_CLASS_NAME}}");
            Executable check = ArtHooks.find_function(owner,
                    "{{PIN_BENEFIT_METHOD_NAME}}", "{{PIN_BENEFIT_METHOD_SIG}}");
            if (check == null) {
                Log.e(TAG, "PinnedChats: " + owner.getName() + " has no benefit check of the shape"
                        + " the finder saw; synced pins stay capped at 3");
                return;
            }
            if (Modifier.isStatic(check.getModifiers())) {
                Log.e(TAG, "PinnedChats: the benefit check is static in this build;"
                        + " synced pins stay capped at 3");
                return;
            }
            Class<?> benefit = check.getParameterTypes()[0];
            if (!benefit.isEnum()) {
                Log.e(TAG, "PinnedChats: " + benefit.getName() + " is not an enum;"
                        + " synced pins stay capped at 3");
                return;
            }
            // The constant's name is a string the app ships, not an obfuscated
            // one; a release without it throws here, before anything is hooked.
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object constant = Enum.valueOf((Class) benefit, "PINNED_CHATS");
            pinnedChats = constant;

            Method replacement = PinnedChats.class.getDeclaredMethod("benefit_hook",
                    Object.class, Object.class);
            Method original = PinnedChats.class.getDeclaredMethod("benefit_backup",
                    Object.class, Object.class);
            boolean hooked = ArtHooks.hook_function(check, replacement, original);
            if (!hooked) {
                pinnedChats = null;
            }
            Log.i(TAG, "PinnedChats: PINNED_CHATS benefit on " + owner.getName()
                    + ".{{PIN_BENEFIT_METHOD_NAME}}, hooked=" + hooked);
        } catch (Throwable t) {
            pinnedChats = null;
            Log.e(TAG, "PinnedChats: synced pins stay capped at 3: " + t);
        }
    }

    /** The trim's run(), or null when it is still in force. */
    private Executable skip_trim() {
        try {
            Class<?> owner = Class.forName("{{PIN_TRIM_CLASS_NAME}}");
            Lambda skip = new Lambda(owner, "{{PIN_TRIM_CASE_FIELD}}", "{{PIN_TRIM_CASE}}");
            Executable run = ArtHooks.find_function(owner,
                    "{{PIN_TRIM_METHOD_NAME}}", "{{PIN_TRIM_METHOD_SIG}}");
            if (run == null || Modifier.isStatic(run.getModifiers())) {
                Log.e(TAG, "PinnedChats: " + owner.getName() + " has no run() of the shape the finder saw;"
                        + " the cap stays");
                return null;
            }
            trim = skip;
            Method replacement = PinnedChats.class.getDeclaredMethod("trim_hook", Object.class);
            Method original = PinnedChats.class.getDeclaredMethod("trim_backup", Object.class);
            boolean hooked = ArtHooks.hook_function(run, replacement, original);
            if (!hooked) {
                trim = null;
                Log.e(TAG, "PinnedChats: hook_function returned false for the trim; the cap stays");
                return null;
            }
            Log.i(TAG, "PinnedChats: trim is case " + skip.lambda + " of " + owner.getName()
                    + ".{{PIN_TRIM_METHOD_NAME}}, hooked=" + hooked);
            return run;
        } catch (Throwable t) {
            trim = null;
            Log.e(TAG, "PinnedChats: the trim stays, and with it the cap: " + t);
            return null;
        }
    }

    private void bypass_gate(Executable trimmed) {
        try {
            Class<?> owner = Class.forName("{{PIN_GATE_CLASS_NAME}}");
            // Resolved before anything is hooked: a gate hooked without the
            // names it needs would only ever hand the pin back to WhatsApp.
            Gate pin = new Gate(owner);
            Executable run = ArtHooks.find_function(owner,
                    "{{PIN_GATE_METHOD_NAME}}", "{{PIN_GATE_METHOD_SIG}}");
            if (run == null || Modifier.isStatic(run.getModifiers())) {
                Log.e(TAG, "PinnedChats: " + owner.getName() + " has no run() of the shape the finder saw;"
                        + " the cap stays");
                return;
            }
            gate = pin;
            if (run.equals(trimmed)) {
                // Already redirected to trim_hook, which answers for the gate too.
                Log.i(TAG, "PinnedChats: gate is case " + pin.lambda + " of " + owner.getName()
                        + ".{{PIN_GATE_METHOD_NAME}}, sharing the trim's redirect");
                return;
            }
            Method replacement = PinnedChats.class.getDeclaredMethod("gate_hook", Object.class);
            Method original = PinnedChats.class.getDeclaredMethod("gate_backup", Object.class);
            boolean hooked = ArtHooks.hook_function(run, replacement, original);
            if (!hooked) {
                gate = null;
            }
            Log.i(TAG, "PinnedChats: gate is case " + pin.lambda + " of " + owner.getName()
                    + ".{{PIN_GATE_METHOD_NAME}}, hooked=" + hooked);
        } catch (Throwable t) {
            gate = null;
            Log.e(TAG, "PinnedChats: the cap stays: " + t);
        }
    }

    public void unload() {
        Log.i(TAG, "PinnedChats: Patch unloaded");
    }
}
