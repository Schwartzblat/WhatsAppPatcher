package com.smali_generator.patches;

import android.os.SystemClock;
import android.util.Log;

import com.arthooks.ArtHooks;

import com.smali_generator.Hook;
import com.smali_generator.HookCategory;
import com.smali_generator.db.PatchDb;
import com.smali_generator.db.SenderJids;
import com.smali_generator.search.SearchQuery;
import com.smali_generator.ui.SenderSearchActivity;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
 * <li>an <b>in-chat search is skipped</b> when the expression carries WhatsApp's
 *     own chat-scope shape, the quoted phrase {@code fts_jid: "0 <token>"}: the
 *     terms this hook adds go on with OR, at the top level, so appending them
 *     under that shape would pull in every other conversation. This guard was
 *     never observed to fire on 2.26.36.71 -- an in-chat search there logged
 *     {@code chatScoped=false} and still stayed in its own chat, because
 *     WhatsApp confines it in the SQL around the MATCH clause, outside the
 *     expression text this hook edits. It stays anyway: this repo has already
 *     been bitten once by assuming the one path a release happened to exercise
 *     was the only path there was;</li>
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

    /**
     * A device run saw a 4-digit query alone produce 126 candidates against the
     * default 64-sender cap -- the default would silently truncate at its own
     * minimum. 5 is still the length of this feature's own worked example
     * ("74164"), so raising it cannot break the case it was built for; 3 and 4
     * stay reachable from the config screen for anyone who wants them.
     */
    public static final int DEFAULT_MIN_DIGITS = 5;
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

    /**
     * Whether the one-shot "actually found somebody" line below has fired.
     *
     * Independent of {@link #loggedMatch}: WhatsApp debounces around 1.5s, so
     * the very first invocation typically carries a single character, below
     * any minimum, and resolves nobody. Every {@link #loggedMatch} line
     * captured on a real device therefore read {@code resolved=0}, which looks
     * like failure even though later calls in the same search resolved rows --
     * this is the line that proves otherwise, whichever call earns it.
     */
    private static volatile boolean loggedHit;

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
        // Timed on any call that could still earn the "first hit" line below,
        // not only the very first call: which invocation is the first to
        // resolve a row is not known in advance, so every untimed candidate
        // would be a resolve this hook cannot ever report the cost of.
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
        return SearchQuery.orTerms(rows, tokenOffset, tokenRadix);
    }

    /**
     * Proves the funnel was actually reached, exactly once, without the typed
     * query in it -- unlike the per-keystroke line this replaced, which
     * printed the tokens themselves and is a privacy smell in a patch whose
     * whole point is reading someone's message index.
     *
     * Fires on the very first call regardless of outcome, so on its own it
     * reads as failure on every debounced first keystroke; {@link
     * #logFirstHit} is the line that says the feature actually did something.
     */
    private static void logFirstMatch(int tokenCount, boolean chatScoped, int rowCount, long resolveMs) {
        loggedMatch = true;
        Log.i(TAG, "SenderSearch: funnel reached, tokens=" + tokenCount + ", chatScoped=" + chatScoped
                + ", resolved=" + rowCount + " jid row(s), first resolve took " + resolveMs + "ms");
    }

    /**
     * Proves the funnel did more than run -- it actually named somebody --
     * exactly once, on whichever call first resolves at least one row. Never
     * the query text, for the same reason {@link #logFirstMatch} omits it.
     */
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

    public String configSummary() {
        return "From " + PatchDb.getInt(MIN_DIGITS_KEY, DEFAULT_MIN_DIGITS) + " digits or "
                + PatchDb.getInt(MIN_NAME_KEY, DEFAULT_MIN_NAME) + " letters, up to "
                + PatchDb.getInt(MAX_SENDERS_KEY, DEFAULT_MAX_SENDERS) + " people";
    }

    public Class<?> configScreen() {
        return SenderSearchActivity.class;
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
            // match_hook's leading Object thiz is the receiver of an instance
            // method. R8 turns an instance method whose receiver goes unused
            // into a static one and leaves the parameter list -- and so the
            // descriptor the finder matches on -- untouched, so a release that
            // staticized the funnel would hook cleanly and shift every argument
            // by one: the query would be read out of searchData and the app
            // handed a String where it expects a context. Declining loudly is
            // the only honest outcome; a wrong hook that logs success is the
            // failure this whole patch is written to avoid.
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
