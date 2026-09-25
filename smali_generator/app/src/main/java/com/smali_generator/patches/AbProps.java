package com.smali_generator.patches;

import android.util.Log;

import com.arthooks.ArtHooks;

import com.smali_generator.Hook;
import com.smali_generator.HookCategory;
import com.smali_generator.abprops.AbPropStore;
import com.smali_generator.ui.AbPropsActivity;

import org.json.JSONObject;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets the app's A/B properties be read and changed.
 *
 * A property is a number. The app asks "what is 33604?" and gets back a
 * boolean, an int, a float, a string or a JSON object depending on which of
 * five accessors it called, and that answer is what decides whether a feature
 * exists for this account. Meta turns them on per account from the server, so a
 * feature can be in the build for months before it is switched on -- and
 * answering one of those questions differently is how you find out what it
 * does.
 *
 * The hook is not on those accessors but on the one private method all five
 * end in, which takes the id and returns the value boxed. The accessors are 8
 * dex code units each: a warm profile's inline caches let dex2oat copy one into
 * a hot call site behind a class check, and the override then silently stopped
 * applying there -- a read of property 16520 on 2.26.37.74 under the phone's
 * own speed-profile. The funnel is some two hundred instructions; no compiler
 * filter inlines it, so every read reaches it however the app was compiled.
 *
 * What the funnel does not know is which accessor asked. That is decided by the
 * type the app's own answer comes back as: the caller casts the funnel's result
 * to Boolean, Number, String or JSONObject, so an override is only ever handed
 * back in the shape the original answer had. It costs the original call on an
 * overridden read, which the accessor hooks used to skip, and buys the one
 * thing that cannot be got any other way: a string property and a JSON one
 * store their overrides as the same text, and handing a String to the JSON
 * accessor's cast would crash the app inside its own code.
 *
 * Reached from 13,336 sites on 2.26.36.71, so this is as hot a path as the
 * patch touches, and off by default for that reason. It is a tool for poking at
 * the app, not something a normal session should be carrying.
 *
 * One subclass of the properties class overrides all five accessors -- the
 * properties object used during registration and Drive restore, 51 references
 * against 635 for the one the rest of the app holds. Its reads reach the funnel
 * too, on its own receiver, and are passed through untouched: those are not
 * paths with features to find in them, and it must not become the object the
 * default tables are read from.
 */
public class AbProps implements Hook {

    private static final String TAG = "PATCH";

    /** The boolean accessor. A receiver whose class declares it itself is the
     *  registration object above, and is left alone. */
    private static final String BOOL_ACCESSOR = "{{AB_PROPS_BOOL_METHOD_NAME}}";

    private static Class<?> owner;

    /** The receiver class every ordinary read comes from, checked before the map. */
    private static volatile Class<?> lastCovered;

    /** Whether reads on a receiver of this class are this hook's business. A
     *  handful of entries: the properties class has three subclasses. */
    private static final Map<Class<?>, Boolean> covered = new ConcurrentHashMap<>();

    // One replacement and backup per number of reference parameters ahead of
    // the id: four on 2.26.17.72, five since. The layout has to match the
    // target's exactly, and Object stands in for every obfuscated type.

    /** native on purpose: a body here would be inlined into the hook and the backup would
     *  silently answer for the original. ArtHooks rewrites its entry point. */
    static native Object funnel3_backup(Object thiz, Object a, Object b, Object c, int id);

    static Object funnel3_hook(Object thiz, Object a, Object b, Object c, int id) {
        return answer(thiz, id, funnel3_backup(thiz, a, b, c, id));
    }

    /** native on purpose: a body here would be inlined into the hook and the backup would
     *  silently answer for the original. ArtHooks rewrites its entry point. */
    static native Object funnel4_backup(Object thiz, Object a, Object b, Object c, Object d, int id);

    static Object funnel4_hook(Object thiz, Object a, Object b, Object c, Object d, int id) {
        return answer(thiz, id, funnel4_backup(thiz, a, b, c, d, id));
    }

    /** native on purpose: a body here would be inlined into the hook and the backup would
     *  silently answer for the original. ArtHooks rewrites its entry point. */
    static native Object funnel5_backup(Object thiz, Object a, Object b, Object c, Object d, Object e,
                                        int id);

    static Object funnel5_hook(Object thiz, Object a, Object b, Object c, Object d, Object e, int id) {
        return answer(thiz, id, funnel5_backup(thiz, a, b, c, d, e, id));
    }

    /** native on purpose: a body here would be inlined into the hook and the backup would
     *  silently answer for the original. ArtHooks rewrites its entry point. */
    static native Object funnel6_backup(Object thiz, Object a, Object b, Object c, Object d, Object e,
                                        Object f, int id);

    static Object funnel6_hook(Object thiz, Object a, Object b, Object c, Object d, Object e, Object f,
                               int id) {
        return answer(thiz, id, funnel6_backup(thiz, a, b, c, d, e, f, id));
    }

    /**
     * What the app is told property {@code id} is, given what it would have
     * been told.
     *
     * Never throws: this runs inside every property read the app makes, on
     * whatever thread got there first. With nothing overridden it costs a
     * class compare, a volatile read and the observation.
     */
    private static Object answer(Object thiz, int id, Object value) {
        try {
            if (!covers(thiz)) {
                return value;
            }
            // Before the override: what the screen shows as the app's own
            // value has to be the app's own, not this patch's answer read back.
            AbPropStore.observe(thiz, id, value instanceof JSONObject ? value.toString() : value);
            AbPropStore.Override override = AbPropStore.override(id);
            if (override == null) {
                return value;
            }
            Object shaped = shape(id, override.value, value);
            if (shaped == null) {
                return value;
            }
            override.served = true;
            return shaped;
        } catch (Throwable t) {
            Log.e(TAG, "AbProps: property " + id + " answered with the app's own value: " + t);
            return value;
        }
    }

    /**
     * The override in the shape the caller will cast to, or null to leave the
     * app's answer alone.
     *
     * The shape is read off the app's own answer, since that is what the
     * accessor's cast was written for. The int and float accessors cast to
     * Number, so either kind of number fits either. With no answer to go by
     * -- the app's value was null -- only a type that cannot be mistaken
     * would be safe, and text can: it is a String to one accessor and a
     * JSONObject to another, and the wrong one is a ClassCastException in the
     * app's own code. So null is left alone, and the override shows as not
     * served.
     */
    static Object shape(int id, Object override, Object original) {
        if (override == null || original == null) {
            return null;
        }
        if (original instanceof Boolean) {
            return override instanceof Boolean ? override : null;
        }
        if (original instanceof Number) {
            return override instanceof Number ? override : null;
        }
        if (original instanceof String) {
            return override instanceof String ? override : null;
        }
        if (original instanceof JSONObject && override instanceof String) {
            // Parsed on each read rather than kept: a shared JSONObject is one
            // the app could edit under us.
            try {
                return new JSONObject((String) override);
            } catch (Throwable t) {
                Log.e(TAG, "AbProps: the override for " + id + " is not JSON, using the app's own value: " + t);
            }
        }
        return null;
    }

    /**
     * Whether reads on this receiver are overridden and observed.
     *
     * The registration object is told apart by what it is rather than by
     * name: it declares the accessors itself, where the object the rest of
     * the app holds inherits them. Decided once per class.
     */
    private static boolean covers(Object thiz) {
        if (thiz == null) {
            return false;
        }
        Class<?> cls = thiz.getClass();
        if (cls == lastCovered) {
            return true;
        }
        Boolean known = covered.get(cls);
        if (known == null) {
            try {
                known = !declaresAccessor(cls);
                Log.i(TAG, "AbProps: reads on " + cls.getName() + (known ? " are covered"
                        : " are left alone, it declares the accessors itself"));
            } catch (Throwable t) {
                // Remembered like an answer, so a class that cannot be looked
                // at costs one log line rather than one per read.
                known = false;
                Log.e(TAG, "AbProps: reads on " + cls.getName() + " are left alone, it could not be inspected: " + t);
            }
            covered.put(cls, known);
        }
        if (known) {
            lastCovered = cls;
        }
        return known;
    }

    /** By exact name and parameters: getDeclaredMethods() would resolve every
     *  signature in an obfuscated class to answer, and can fail on any one. */
    private static boolean declaresAccessor(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != owner; c = c.getSuperclass()) {
            try {
                c.getDeclaredMethod(BOOL_ACCESSOR, int.class);
                return true;
            } catch (NoSuchMethodException ignored) {
                // Inherited here; keep walking up to the properties class.
            }
        }
        return false;
    }

    public String id() {
        return "ab_props";
    }

    public String title() {
        return "A/B property overrides";
    }

    public String description() {
        return "Read and change the app's own feature flags.";
    }

    public HookCategory category() {
        return HookCategory.DEVELOPER;
    }

    /** Off unless asked for: a hook on the app's hottest read path, in aid of
     *  a screen most sessions have no use for. */
    public boolean defaultEnabled() {
        return false;
    }

    public String configSummary() {
        // Read here as well as at load: this is called to draw the settings
        // screen, which is reachable with the hook switched off.
        AbPropStore.load();
        int count = AbPropStore.count();
        return count == 0 ? "Browse and override properties"
                : count == 1 ? "1 property overridden" : count + " properties overridden";
    }

    public Class<?> configScreen() {
        return AbPropsActivity.class;
    }

    public void load() {
        // Before anything is hooked: a read that arrived between the redirect
        // and the overrides would answer with the app's own value.
        AbPropStore.load();
        try {
            owner = Class.forName("{{AB_PROPS_CLASS_NAME}}");
            Executable funnel = ArtHooks.find_function(owner,
                    "{{AB_PROPS_FUNNEL_METHOD_NAME}}", "{{AB_PROPS_FUNNEL_METHOD_SIG}}");
            if (funnel == null) {
                Log.e(TAG, "AbProps: " + owner.getName() + " has no funnel of the shape the finder saw");
                return;
            }
            // R8 makes an instance method static when its receiver goes unused,
            // without touching the descriptor the finder matched. Hooking a
            // static one installs cleanly and then dies on the first call, when
            // the backup re-enters the replacement -- outside every try/catch,
            // with the app already running.
            if (Modifier.isStatic(funnel.getModifiers())) {
                Log.e(TAG, "AbProps: the funnel is static in this build, nothing is hooked");
                return;
            }
            Class<?>[] params = ((Method) funnel).getParameterTypes();
            int references = params.length - 1;
            if (references < 0 || params[references] != int.class
                    || ((Method) funnel).getReturnType() != Object.class) {
                Log.e(TAG, "AbProps: the funnel does not take the id last and return Object: " + funnel);
                return;
            }
            for (int i = 0; i < references; i++) {
                if (params[i].isPrimitive()) {
                    Log.e(TAG, "AbProps: the funnel takes a primitive before the id: " + funnel);
                    return;
                }
            }
            Class<?>[] shape = new Class<?>[references + 2];
            Arrays.fill(shape, Object.class);
            shape[references + 1] = int.class;
            Method replacement;
            Method original;
            try {
                replacement = AbProps.class.getDeclaredMethod("funnel" + references + "_hook", shape);
                original = AbProps.class.getDeclaredMethod("funnel" + references + "_backup", shape);
            } catch (NoSuchMethodException e) {
                Log.e(TAG, "AbProps: the funnel takes " + references
                        + " references before the id, and no replacement has that shape");
                return;
            }
            boolean hooked = ArtHooks.hook_function(funnel, replacement, original);
            Log.i(TAG, "AbProps: hooked the funnel " + owner.getName() + "."
                    + ((Method) funnel).getName() + "{{AB_PROPS_FUNNEL_METHOD_SIG}}, hooked=" + hooked);
        } catch (Throwable t) {
            Log.e(TAG, "AbProps: the properties class was not reached, nothing is hooked: " + t);
        }
    }

    public void unload() {
        Log.i(TAG, "AbProps: Patch unloaded");
    }
}
