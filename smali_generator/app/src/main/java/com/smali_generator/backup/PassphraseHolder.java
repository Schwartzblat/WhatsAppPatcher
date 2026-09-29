package com.smali_generator.backup;

/**
 * The passphrase for the current process, and nowhere else.
 *
 * It is deliberately not in PatchDb: storing it beside the wrapped key in the
 * app's own data would make the wrapping ceremonial. Only a verifier is
 * persisted. The cost is that a scheduled run can wrap a key only while the
 * user has entered the passphrase this launch -- so the screen offers to keep
 * it for the session, and a run without it uploads the database alone.
 */
public final class PassphraseHolder {

    private static volatile String passphrase;

    private PassphraseHolder() {
    }

    public static void set(String value) {
        passphrase = value;
    }

    public static String get() {
        return passphrase;
    }

    public static void clear() {
        passphrase = null;
    }
}
