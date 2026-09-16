package com.smali_generator.search;

import java.util.ArrayList;
import java.util.List;

/** The half-built MATCH expression the hooked method is handed: nothing else carries the typed words. */
public final class SearchQuery {

    private static final String CONTENT_PREFIX = "content:";

    /**
     * WhatsApp's own chat-scope shape: a resolved contact is {@code fts_jid:<token>}, a chat scope
     * the quoted phrase {@code fts_jid: "0 <token>"}. Never seen to fire on 2.26.36.71, where the
     * SQL around the MATCH clause does the confining, but the terms this appends go on with OR at
     * the top level and under that shape would pull in every other conversation.
     */
    private static final String CHAT_SCOPE_MARK = "fts_jid: \"";

    private SearchQuery() {
    }

    public static boolean isChatScoped(String expression) {
        return expression != null && expression.contains(CHAT_SCOPE_MARK);
    }

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
     * The digits of {@code token} if it is a phone-number fragment, else "" -- the empty answer is
     * what routes a word to the name search, not an error. Separators are dropped, a number being
     * typeable with them.
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
     * What the index calls a jid row. Offset and radix are the app's, resolved by the finder rather
     * than written down here, so a build that changes either fails at patch time, not silently.
     */
    public static String token(long jidRowId, int offset, int radix) {
        return Long.toString(jidRowId + offset, radix);
    }

    /**
     * Terms that OR onto the end of a finished expression, each repeating the scope suffix it
     * already carries: AND binds tighter than OR in FTS3/4, so {@code a ns OR b} parses as
     * {@code a OR (b AND ns)} and strips the namespace scoping off what the user typed. The hooked
     * method appends its own terms rather than prepending them, verified in the smali of three releases.
     */
    public static String orTerms(List<Long> jidRowIds, int offset, int radix, String suffix) {
        String scope = suffix == null ? "" : suffix;
        StringBuilder terms = new StringBuilder();
        for (Long jidRowId : jidRowIds) {
            terms.append(" OR fts_jid:").append(token(jidRowId, offset, radix)).append(scope);
        }
        return terms.toString();
    }
}
