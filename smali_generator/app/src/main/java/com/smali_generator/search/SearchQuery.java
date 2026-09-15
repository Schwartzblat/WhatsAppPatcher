package com.smali_generator.search;

import java.util.ArrayList;
import java.util.List;

/**
 * The FTS MATCH expression, read and extended.
 *
 * WhatsApp hands the method this patch hooks a half-built expression: one
 * {@code content:} term per word typed, with a trailing {@code *} on the last,
 * and -- when the search is scoped to one chat -- the chat's own terms. There
 * is no separate place to ask what the user typed, so it is read back out of
 * the expression, which is also the only form guaranteed to be the thing the
 * query will actually run with.
 *
 * Deliberately free of Android imports: this is the part worth unit testing,
 * and none of it needs a device.
 */
public final class SearchQuery {

    private static final String CONTENT_PREFIX = "content:";

    /**
     * The shape chat scoping is written in, and nothing else is.
     *
     * A resolved contact is {@code fts_jid:<token>}; a chat scope is
     * {@code fts_jid: "0 <token>"} -- a phrase, with a space after the colon.
     * Telling them apart is what keeps an in-chat search in its chat.
     */
    private static final String CHAT_SCOPE_MARK = "fts_jid: \"";

    private SearchQuery() {
    }

    /** Whether this search is confined to one conversation. */
    public static boolean isChatScoped(String expression) {
        return expression != null && expression.contains(CHAT_SCOPE_MARK);
    }

    /** The words the user typed, in order, with the prefix marker removed. */
    public static List<String> tokensOf(String expression) {
        List<String> tokens = new ArrayList<>();
        if (expression == null) {
            return tokens;
        }
        for (String part : expression.split("\\s+")) {
            if (!part.startsWith(CONTENT_PREFIX)) {
                continue;
            }
            String token = part.substring(CONTENT_PREFIX.length());
            while (token.endsWith("*")) {
                token = token.substring(0, token.length() - 1);
            }
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * The digits of {@code token} if it is a phone-number fragment, else "".
     *
     * A separator is allowed and dropped, because a number can be typed with
     * them. Any other character means this is a name, not a number, and the
     * empty answer is what routes it to the name search instead.
     */
    public static String digitsOf(String token) {
        if (token == null) {
            return "";
        }
        StringBuilder digits = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (c != '+' && c != '-' && c != '(' && c != ')' && c != ' ') {
                return "";
            }
        }
        return digits.toString();
    }

    /**
     * What the index calls a jid row.
     *
     * The offset and radix are the app's, resolved by the finder, so a build
     * that changes either is a patch-time failure rather than a search that
     * silently matches nothing.
     */
    public static String token(long jidRowId, int offset, int radix) {
        return Long.toString(jidRowId + offset, radix);
    }

    /**
     * Terms that OR onto the end of an expression.
     *
     * They go at the end so the namespace terms the hooked method appends stay
     * ANDed around the whole group: FTS3 has no parentheses to say that with,
     * only left-to-right evaluation.
     */
    public static String orTerms(List<Long> jidRowIds, int offset, int radix) {
        StringBuilder terms = new StringBuilder();
        for (Long jidRowId : jidRowIds) {
            terms.append(" OR fts_jid:").append(token(jidRowId, offset, radix));
        }
        return terms.toString();
    }
}
