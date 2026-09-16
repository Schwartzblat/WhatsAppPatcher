package com.smali_generator.patches;

import android.os.SystemClock;
import android.util.Log;

import com.arthooks.ArtHooks;

import com.smali_generator.Hook;
import com.smali_generator.HookCategory;
import com.smali_generator.db.PatchDb;
import com.smali_generator.db.SenderJids;
import com.smali_generator.search.SearchQuery;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * Resolves a search query to the people it names and appends their sender terms to the FTS
 * expression, leaving WhatsApp's own SQL, ranking and rows to draw the results. Anything it
 * cannot answer appends nothing, which is the search the app already does.
 */
public class SenderSearch implements Hook {

    private static final String TAG = "PATCH";

    public static final String MIN_DIGITS_KEY = "sender_search_min_digits";
    public static final String MIN_NAME_KEY = "sender_search_min_name";
    public static final String MAX_SENDERS_KEY = "sender_search_max_senders";

    /**
     * A 4-digit query alone produced 126 candidates on a device run, against a 64-sender cap that
     * would have truncated it silently; 5 is still the length of this feature's worked example
     * ("74164"). All three are read through {@link PatchDb#getInt}, so a screen setting them is only a screen.
     */
    public static final int DEFAULT_MIN_DIGITS = 5;
    public static final int DEFAULT_MIN_NAME = 3;
    /** Bounds the expression: every sender is one more term FTS has to union. */
    public static final int DEFAULT_MAX_SENDERS = 64;

    /** The index's encoding: written once on the startup thread, read on whichever thread the search runs on. */
    private static volatile int tokenOffset;
    private static volatile int tokenRadix;

    /**
     * One-shot: the search worker calls this on every keystroke, so a line per call would be noise
     * and a place the typed query could leak into logcat -- which is why neither line carries it.
     */
    private static volatile boolean loggedMatch;

    /**
     * One-shot, and separate from {@link #loggedMatch} because WhatsApp debounces: the first call
     * carries one character and resolves nobody, so every {@code loggedMatch} line captured on a
     * device read {@code resolved=0}. This is the line that says otherwise.
     */
    private static volatile boolean loggedHit;

    /** native on purpose: a body gets inlined by dex2oat and the backup then silently answers for the original. */
    static native String match_backup(Object thiz, Object context, Object searchData, String expression);

    static String match_hook(Object thiz, Object context, Object searchData, String expression) {
        // Called first, on the untouched expression: its answer is both the query
        // the app would have run and the only place to read the scope terms it
        // appends, which every added term has to repeat. Outside the try because
        // the funnel throws IllegalStateException on an all-NOT query, which is the app's throw.
        String finished = match_backup(thiz, context, searchData, expression);
        try {
            // Never returned so far, but concatenating onto it would hand
            // SQLite the four characters "null" as a search term.
            if (finished == null) {
                return null;
            }
            String terms = senderTerms(expression, suffixOf(expression, finished));
            if (!terms.isEmpty()) {
                return finished + terms;
            }
        } catch (Throwable t) {
            // The user is waiting on this query, and what the app built is always a valid answer.
            Log.e(TAG, "SenderSearch: the query was left as WhatsApp built it", t);
        }
        return finished;
    }

    /**
     * What the hooked method appended to {@code expression} -- the namespace terms every added term
     * repeats, see {@link SearchQuery#orTerms} -- or "" when it appended nothing recognisable.
     * Guessing a suffix out of a string this did not recognise is the one way to corrupt a query.
     */
    private static String suffixOf(String expression, String finished) {
        if (expression == null || finished == null
                || !finished.startsWith(expression) || finished.length() == expression.length()) {
            return "";
        }
        return finished.substring(expression.length());
    }

    private static String senderTerms(String expression, String suffix) {
        // Before any early return: every branch has to be able to fire the
        // proof-of-life line, not only the one reaching SenderJids.resolve.
        boolean firstCall = !loggedMatch;
        if (expression == null || expression.isEmpty() || SearchQuery.isChatScoped(expression)) {
            if (firstCall) {
                logFirstMatch(0, SearchQuery.isChatScoped(expression), 0, 0);
            }
            return "";
        }
        List<String> tokens = SearchQuery.tokensOf(expression);
        if (tokens.isEmpty()) {
            if (firstCall) {
                logFirstMatch(0, false, 0, 0);
            }
            return "";
        }
        // Any call that could still earn the first-hit line is timed: which one first
        // resolves a row is not known in advance, so an untimed one is a cost never reported.
        boolean timeThisCall = firstCall || !loggedHit;
        long start = timeThisCall ? SystemClock.elapsedRealtime() : 0;
        List<Long> rows = SenderJids.resolve(tokens,
                PatchDb.getInt(MIN_DIGITS_KEY, DEFAULT_MIN_DIGITS),
                PatchDb.getInt(MIN_NAME_KEY, DEFAULT_MIN_NAME),
                PatchDb.getInt(MAX_SENDERS_KEY, DEFAULT_MAX_SENDERS));
        long elapsed = timeThisCall ? SystemClock.elapsedRealtime() - start : 0;
        if (firstCall) {
            logFirstMatch(tokens.size(), false, rows.size(), elapsed);
        }
        if (!rows.isEmpty() && !loggedHit) {
            logFirstHit(tokens.size(), rows.size(), elapsed);
        }
        if (rows.isEmpty()) {
            return "";
        }
        return SearchQuery.orTerms(rows, tokenOffset, tokenRadix, suffix);
    }

    private static void logFirstMatch(int tokenCount, boolean chatScoped, int rowCount, long resolveMs) {
        loggedMatch = true;
        Log.i(TAG, "SenderSearch: funnel reached, tokens=" + tokenCount + ", chatScoped=" + chatScoped
                + ", resolved=" + rowCount + " jid row(s), first resolve took " + resolveMs + "ms");
    }

    private static void logFirstHit(int tokenCount, int rowCount, long resolveMs) {
        loggedHit = true;
        Log.i(TAG, "SenderSearch: funnel matched somebody, tokens=" + tokenCount
                + ", resolved=" + rowCount + " jid row(s), resolve took " + resolveMs + "ms");
    }

    public String id() {
        return "sender_search";
    }

    public String title() {
        return "Find messages by who sent them";
    }

    public String description() {
        return "Searching part of a number, or a name, also finds every message that person sent in any chat.";
    }

    public HookCategory category() {
        return HookCategory.SEARCH;
    }

    public void load() {
        try {
            // Parsed rather than inlined: an unsubstituted placeholder then fails
            // here, before anything is hooked, instead of becoming an encoding
            // that silently names no message.
            tokenOffset = Integer.parseInt("{{MESSAGE_SEARCH_TOKEN_OFFSET}}");
            tokenRadix = Integer.parseInt("{{MESSAGE_SEARCH_TOKEN_RADIX}}");
            Class<?> owner = Class.forName("{{MESSAGE_SEARCH_CLASS_NAME}}");

            Executable funnel = ArtHooks.find_function(owner,
                    "{{MESSAGE_SEARCH_METHOD_NAME}}", "{{MESSAGE_SEARCH_METHOD_SIG}}");
            // R8 staticizes an instance method whose receiver goes unused without
            // touching the descriptor the finder matches, so a staticized funnel
            // would hook cleanly with every argument shifted by one. Declining
            // loudly beats a wrong hook that logs success.
            if (Modifier.isStatic(funnel.getModifiers())) {
                Log.e(TAG, "SenderSearch: the funnel is static on this build, its shape changed;"
                        + " search was left as it was");
                return;
            }
            Method replacement = SenderSearch.class.getDeclaredMethod("match_hook",
                    Object.class, Object.class, Object.class, String.class);
            Method original = SenderSearch.class.getDeclaredMethod("match_backup",
                    Object.class, Object.class, Object.class, String.class);
            boolean hooked = ArtHooks.hook_function(funnel, replacement, original);
            if (!hooked) {
                // false means search runs unmodified with no exception anywhere --
                // the same silence as success, so it needs a line of its own.
                Log.e(TAG, "SenderSearch: hook_function returned false, search was left as it was");
            }

            Log.i(TAG, "SenderSearch: hooked on " + owner.getName()
                    + ".{{MESSAGE_SEARCH_METHOD_NAME}}, tokens are base " + tokenRadix
                    + " from " + tokenOffset + ", hooked=" + hooked);
        } catch (Throwable t) {
            Log.e(TAG, "SenderSearch: search was left as it was: " + t);
        }
    }

    public void unload() {
        Log.i(TAG, "SenderSearch: Patch unloaded");
    }
}
