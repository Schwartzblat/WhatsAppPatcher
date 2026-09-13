package com.smali_generator;

import com.smali_generator.db.PatchDb;

public interface Hook {

    public void load();

    public void unload();

    /**
     * Stable key for this hook's on/off flag.
     *
     * It is a database key, so it must not change once shipped: renaming one
     * silently resets that hook to its default. Defaults to the class name,
     * which is stable because the module is built without minification.
     */
    default String id() {
        return getClass().getSimpleName();
    }

    /** One short line naming the hook on the settings screen. */
    default String title() {
        return id();
    }

    /** What the hook does, in a sentence, for the settings screen. */
    default String description() {
        return "";
    }

    /**
     * False for the hooks the patched app needs to work at all -- the
     * signature and APK-integrity bypasses, the Firebase key, and the entry
     * point to the settings screen itself. Those are listed but not switchable.
     */
    default boolean toggleable() {
        return true;
    }

    default boolean defaultEnabled() {
        return true;
    }

    /**
     * Which section of the settings screen this hook belongs to.
     *
     * The default is deliberately a real section rather than a guess: a hook
     * that forgets to override this is listed under "Other" instead of
     * disappearing from the screen.
     */
    default HookCategory category() {
        return toggleable() ? HookCategory.OTHER : HookCategory.MANDATORY;
    }

    /**
     * Whether {@link InitProvider} should install this hook.
     *
     * Read once per hook at startup, which is why turning one off only takes
     * effect on the next launch: ArtHooks installs a redirect, and nothing
     * removes one.
     */
    default boolean isEnabled() {
        if (!toggleable()) {
            return true;
        }
        return PatchDb.getFlag(id(), defaultEnabled());
    }
}
