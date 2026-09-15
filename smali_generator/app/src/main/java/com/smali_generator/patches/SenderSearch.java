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
import java.util.List;

/**
 * Makes a search for a number or a name find what that person sent.
 *
 * Searching WhatsApp for a number finds the chat with it. It does not find the
 * messages that number posted in a group, because the query never becomes a
 * sender: WhatsApp hands the typed word to its own contact search, which
 * matches a number by prefix and normalised form rather than from the middle,
 * and only consults it at all when a server flag says it may. On an account
 * where that flag is off nothing is resolved to a person at all.
 *
 * The index can already answer the question. Its {@code fts_jid} column holds
 * two tokens per message -- who sent it and which chat it is in -- so a term
 * naming a sender is all that is missing. This hook resolves the query to jid
 * rows itself and appends those terms, leaving the rest of search alone:
 * WhatsApp's own SQL, ranking and rows draw the results.
 *
 * Three things keep it from being louder than it should be:
 *
 * <ul>
 * <li>an <b>in-chat search is left alone</b>. The terms go on with OR, at the
 *     top level, so adding them to a search confined to one conversation would
 *     pull in every other one;</li>
 * <li>a <b>quoted query is left alone</b>, since it carries no content terms
 *     and is a request for an exact phrase;</li>
 * <li>failure appends <b>nothing</b>, which is the search the app already
 *     does.</li>
 * </ul>
 */
public class SenderSearch implements Hook {

    private static final String TAG = "PATCH";

    public static final String MIN_DIGITS_KEY = "sender_search_min_digits";
    public static final String MIN_NAME_KEY = "sender_search_min_name";
    public static final String MAX_SENDERS_KEY = "sender_search_max_senders";

    /** Below four digits a query matches most of the jid table. */
    public static final int DEFAULT_MIN_DIGITS = 4;
    public static final int DEFAULT_MIN_NAME = 3;
    /** Bounds the expression: every sender is one more term FTS has to union. */
    public static final int DEFAULT_MAX_SENDERS = 64;

    /**
     * The index's encoding, resolved at load.
     *
     * Volatile because the hook runs on whatever thread the search is on, and
     * these are written once on the startup thread.
     */
    private static volatile int tokenOffset;
    private static volatile int tokenRadix;

    /**
     * Whether the one-shot proof-of-life line below has fired.
     *
     * Same pattern as {@code loggedTabs}/{@code loggedButton}/{@code loggedCalls} in
     * {@code MetaAiButton}: the search worker calls this on every keystroke, so logging
     * every call would be both noise and a place the typed query could leak into logcat.
     */
    private static volatile boolean loggedMatch;

    /** native on purpose: a body here would be inlined into the hook and the backup would
     * silently answer for the original. ArtHooks rewrites its entry point. */
    static native String match_backup(Object thiz, Object context, Object searchData, String expression);

    static String match_hook(Object thiz, Object context, Object searchData, String expression) {
        String extended = expression;
        try {
            String terms = senderTerms(expression);
            if (!terms.isEmpty()) {
                extended = expression + terms;
            }
        } catch (Throwable t) {
            // A throw here lands in the middle of building a query the user is
            // waiting on. The unmodified expression is always a valid answer.
            Log.e(TAG, "SenderSearch: the query was left as WhatsApp built it", t);
        }
        return match_backup(thiz, context, searchData, extended);
    }

    private static String senderTerms(String expression) {
        // Captured before any early return: whichever branch this call takes
        // is the one the proof-of-life line describes, so it must fire from
        // all of them -- not only the one that reaches SenderJids.resolve.
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
        // Timed only on the first call: the question this answers is "how
        // long did the one-time table read cost", not a per-keystroke metric.
        long start = firstCall ? SystemClock.elapsedRealtime() : 0;
        List<Long> rows = SenderJids.resolve(tokens,
                PatchDb.getInt(MIN_DIGITS_KEY, DEFAULT_MIN_DIGITS),
                PatchDb.getInt(MIN_NAME_KEY, DEFAULT_MIN_NAME),
                PatchDb.getInt(MAX_SENDERS_KEY, DEFAULT_MAX_SENDERS));
        if (firstCall) {
            logFirstMatch(tokens.size(), false, rows.size(), SystemClock.elapsedRealtime() - start);
        }
        if (rows.isEmpty()) {
            return "";
        }
        return SearchQuery.orTerms(rows, tokenOffset, tokenRadix);
    }

    /**
     * Proves the funnel was actually reached, exactly once, without the typed
     * query in it -- unlike the per-keystroke line this replaced, which
     * printed the tokens themselves and is a privacy smell in a patch whose
     * whole point is reading someone's message index.
     */
    private static void logFirstMatch(int tokenCount, boolean chatScoped, int rowCount, long resolveMs) {
        loggedMatch = true;
        Log.i(TAG, "SenderSearch: funnel reached, tokens=" + tokenCount + ", chatScoped=" + chatScoped
                + ", resolved=" + rowCount + " jid row(s), first resolve took " + resolveMs + "ms");
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

    public String configSummary() {
        return "From " + PatchDb.getInt(MIN_DIGITS_KEY, DEFAULT_MIN_DIGITS) + " digits or "
                + PatchDb.getInt(MIN_NAME_KEY, DEFAULT_MIN_NAME) + " letters, up to "
                + PatchDb.getInt(MAX_SENDERS_KEY, DEFAULT_MAX_SENDERS) + " people";
    }

    public void load() {
        try {
            // Parsed rather than inlined as literals: an unsubstituted
            // placeholder then fails here, before anything is hooked, instead
            // of becoming an encoding that silently names no message.
            tokenOffset = Integer.parseInt("{{MESSAGE_SEARCH_TOKEN_OFFSET}}");
            tokenRadix = Integer.parseInt("{{MESSAGE_SEARCH_TOKEN_RADIX}}");
            Class<?> owner = Class.forName("{{MESSAGE_SEARCH_CLASS_NAME}}");

            Executable funnel = ArtHooks.find_function(owner,
                    "{{MESSAGE_SEARCH_METHOD_NAME}}", "{{MESSAGE_SEARCH_METHOD_SIG}}");
            Method replacement = SenderSearch.class.getDeclaredMethod("match_hook",
                    Object.class, Object.class, Object.class, String.class);
            Method original = SenderSearch.class.getDeclaredMethod("match_backup",
                    Object.class, Object.class, Object.class, String.class);
            boolean hooked = ArtHooks.hook_function(funnel, replacement, original);
            if (!hooked) {
                // hook_function returning false means search runs unmodified
                // with no exception anywhere -- the same silence as success,
                // so it needs its own line at error level to be found at all.
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
