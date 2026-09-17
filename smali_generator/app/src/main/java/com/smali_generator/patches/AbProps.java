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
 * All five accessors are hooked, because a property's type decides which one it
 * goes through and there is no saying in advance which type an interesting
 * property is. Between them they are reached from 13,336 sites on 2.26.36.71,
 * 10,420 of them the boolean one, so this is as hot a path as the patch
 * touches: with nothing overridden the replacement is a single volatile read
 * and a call to the backup.
 *
 * Off by default for that reason. It is a tool for poking at the app, not
 * something a normal session should be carrying.
 *
 * One subclass of the properties class overrides all five accessors and so goes
 * past a hook placed on the base -- the properties object used during
 * registration and Drive restore, 51 references against 635 for the one the
 * rest of the app holds. It is deliberately not covered: those are not paths
 * with features to find in them.
 */
public class AbProps implements Hook {

    private static final String TAG = "PATCH";

    /** native on purpose: a body here would be inlined into the hook and the backup would
     * silently answer for the original. ArtHooks rewrites its entry point. */
    static native boolean bool_backup(Object thiz, int id);

    static boolean bool_hook(Object thiz, int id) {
        AbPropStore.Override override = AbPropStore.override(id);
        if (override != null && override.value instanceof Boolean) {
            override.served = true;
            return (Boolean) override.value;
        }
        boolean value = bool_backup(thiz, id);
        AbPropStore.observe(thiz, id, value);
        return value;
    }

    /** native on purpose: a body here would be inlined into the hook and the backup would
     * silently answer for the original. ArtHooks rewrites its entry point. */
    static native int int_backup(Object thiz, int id);

    static int int_hook(Object thiz, int id) {
        AbPropStore.Override override = AbPropStore.override(id);
        if (override != null && override.value instanceof Integer) {
            override.served = true;
            return (Integer) override.value;
        }
        int value = int_backup(thiz, id);
        AbPropStore.observe(thiz, id, value);
        return value;
    }

    /** native on purpose: a body here would be inlined into the hook and the backup would
     * silently answer for the original. ArtHooks rewrites its entry point. */
    static native float float_backup(Object thiz, int id);

    static float float_hook(Object thiz, int id) {
        AbPropStore.Override override = AbPropStore.override(id);
        if (override != null && override.value instanceof Float) {
            override.served = true;
            return (Float) override.value;
        }
        float value = float_backup(thiz, id);
        AbPropStore.observe(thiz, id, value);
        return value;
    }

    /** native on purpose: a body here would be inlined into the hook and the backup would
     * silently answer for the original. ArtHooks rewrites its entry point. */
    static native String string_backup(Object thiz, int id);

    static String string_hook(Object thiz, int id) {
        AbPropStore.Override override = AbPropStore.override(id);
        if (override != null && override.value instanceof String) {
            override.served = true;
            return (String) override.value;
        }
        String value = string_backup(thiz, id);
        AbPropStore.observe(thiz, id, value);
        return value;
    }

    /** native on purpose: a body here would be inlined into the hook and the backup would
     * silently answer for the original. ArtHooks rewrites its entry point. */
    static native JSONObject json_backup(Object thiz, int id);

    /**
     * The JSON accessor. Its overrides are stored as the same text as a
     * string property's, because the two default tables are indistinguishable
     * by value class -- so this is where text meant for this accessor is read
     * as JSON, and text that is not JSON leaves the app's own answer alone.
     *
     * Parsed on each read rather than kept: there are 143 call sites against
     * the boolean accessor's 10,420, and a shared JSONObject is one the app
     * could edit under us.
     */
    static JSONObject json_hook(Object thiz, int id) {
        AbPropStore.Override override = AbPropStore.override(id);
        if (override != null && override.value instanceof String) {
            try {
                JSONObject parsed = new JSONObject((String) override.value);
                override.served = true;
                return parsed;
            } catch (Throwable t) {
                Log.e(TAG, "AbProps: the override for " + id + " is not JSON, using the app's own value: " + t);
            }
        }
        JSONObject value = json_backup(thiz, id);
        AbPropStore.observe(thiz, id, value == null ? null : value.toString());
        return value;
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

    /** Off unless asked for: five hooks on the app's hottest read path, in aid
     *  of a screen most sessions have no use for. */
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
        // Before anything is hooked: an accessor that fired between being
        // redirected and the overrides arriving would answer with the app's own
        // value and be recorded as though nothing was set.
        AbPropStore.load();
        try {
            Class<?> owner = Class.forName("{{AB_PROPS_CLASS_NAME}}");
            install(owner, "boolean", "{{AB_PROPS_BOOL_METHOD_NAME}}", "{{AB_PROPS_BOOL_METHOD_SIG}}",
                    "bool_hook", "bool_backup", boolean.class);
            install(owner, "int", "{{AB_PROPS_INT_METHOD_NAME}}", "{{AB_PROPS_INT_METHOD_SIG}}",
                    "int_hook", "int_backup", int.class);
            install(owner, "float", "{{AB_PROPS_FLOAT_METHOD_NAME}}", "{{AB_PROPS_FLOAT_METHOD_SIG}}",
                    "float_hook", "float_backup", float.class);
            install(owner, "string", "{{AB_PROPS_STRING_METHOD_NAME}}", "{{AB_PROPS_STRING_METHOD_SIG}}",
                    "string_hook", "string_backup", String.class);
            install(owner, "json", "{{AB_PROPS_JSON_METHOD_NAME}}", "{{AB_PROPS_JSON_METHOD_SIG}}",
                    "json_hook", "json_backup", JSONObject.class);
        } catch (Throwable t) {
            Log.e(TAG, "AbProps: the properties class was not reached, nothing is hooked: " + t);
        }
    }

    /**
     * One accessor, in its own try/catch: a release that has moved one of the
     * five should still leave the other four readable and overridable.
     *
     * The return type is passed rather than inferred so that the replacement
     * and the backup are looked up by their exact shapes; a mismatch is a
     * NoSuchMethodException here rather than a redirect into the wrong method.
     */
    private void install(Class<?> owner, String label, String name, String signature,
                         String replacementName, String backupName, Class<?> returns) {
        try {
            Executable target = ArtHooks.find_function(owner, name, signature);
            // R8 makes an instance method static when its receiver goes unused,
            // without touching the descriptor the finder matched. Hooking a
            // static one installs cleanly and then dies on the first call, when
            // the backup re-enters the replacement -- outside every try/catch,
            // with the app already running.
            if (Modifier.isStatic(target.getModifiers())) {
                Log.e(TAG, "AbProps: " + owner.getName() + "." + name + signature
                        + " is static in this build; the " + label + " accessor is left alone");
                return;
            }
            Method replacement = AbProps.class.getDeclaredMethod(replacementName, Object.class, int.class);
            Method original = AbProps.class.getDeclaredMethod(backupName, Object.class, int.class);
            if (replacement.getReturnType() != returns || original.getReturnType() != returns) {
                Log.e(TAG, "AbProps: the " + label + " replacement does not return " + returns.getName());
                return;
            }
            boolean hooked = ArtHooks.hook_function(target, replacement, original);
            Log.i(TAG, "AbProps: " + label + " accessor hooked on " + owner.getName() + "."
                    + name + signature + ", hooked=" + hooked);
        } catch (Throwable t) {
            Log.e(TAG, "AbProps: the " + label + " accessor was left alone: " + t);
        }
    }

    public void unload() {
        Log.i(TAG, "AbProps: Patch unloaded");
    }
}
