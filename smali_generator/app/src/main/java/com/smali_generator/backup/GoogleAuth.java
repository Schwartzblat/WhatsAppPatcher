package com.smali_generator.backup;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.smali_generator.db.PatchDb;

/**
 * Tokens for Drive, through the framework's AccountManager rather than any
 * Google library.
 *
 * Measured on 2026-09-30 from inside WhatsApp's process: drive.appdata returned
 * a real token and drive.file returned KEY_INTENT, which proves this route
 * reaches the same authenticator GoogleAuthUtil does, against the OAuth client
 * registered for (com.whatsapp, this keystore's SHA-1). No library is added
 * because WhatsApp already ships a shaded com.google.android.gms.auth.* and this
 * module is not minified -- a second copy of those classes would share a process
 * with the host's.
 */
public final class GoogleAuth {

    private static final String TAG = "PATCH";
    private static final String ACCOUNT_TYPE = "com.google";

    /** The narrowest scope that works: it only ever sees files this app created. */
    public static final String SCOPE = "oauth2:https://www.googleapis.com/auth/drive.file";

    public static final String ACCOUNT_KEY = "drive_backup_account";

    private GoogleAuth() {
    }

    public static class Token {
        public final String value;
        public final Intent consent;

        Token(String value, Intent consent) {
            this.value = value;
            this.consent = consent;
        }
    }

    public static String accountName() {
        return PatchDb.getString(ACCOUNT_KEY, null);
    }

    public static void setAccountName(String name) {
        PatchDb.setString(ACCOUNT_KEY, name == null ? "" : name);
    }

    /**
     * The framework picker rather than enumeration. getAccountsByType returned
     * an account on the test device, but account visibility is granted per app
     * on API 26+, so a device where it returns nothing must still be able to
     * choose one.
     */
    public static Intent chooserIntent() {
        return AccountManager.newChooseAccountIntent(
                null, null, new String[]{ACCOUNT_TYPE}, null, null, null, null);
    }

    /**
     * Blocks. Never call from the main thread.
     *
     * A consent intent is not an error: it is what a scope the user has not yet
     * granted looks like, and the screen turns it into a button.
     */
    public static Token token(Context context) {
        String name = accountName();
        if (name == null || name.isEmpty()) {
            Log.i(TAG, "GoogleAuth: no account chosen yet");
            return new Token(null, null);
        }
        try {
            Bundle result = AccountManager.get(context)
                    .getAuthToken(new Account(name, ACCOUNT_TYPE), SCOPE, null, false, null, null)
                    .getResult();
            String token = result.getString(AccountManager.KEY_AUTHTOKEN);
            Intent consent = result.getParcelable(AccountManager.KEY_INTENT);
            Log.i(TAG, "GoogleAuth: token=" + (token != null) + " consent=" + (consent != null));
            return new Token(token, consent);
        } catch (Throwable t) {
            Log.e(TAG, "GoogleAuth: cannot get a token", t);
            return new Token(null, null);
        }
    }

    /** After a 401, so the next call mints a fresh one instead of replaying the dead one. */
    public static void invalidate(Context context, String token) {
        try {
            AccountManager.get(context).invalidateAuthToken(ACCOUNT_TYPE, token);
            Log.i(TAG, "GoogleAuth: token invalidated");
        } catch (Throwable t) {
            Log.e(TAG, "GoogleAuth: invalidate failed", t);
        }
    }
}
