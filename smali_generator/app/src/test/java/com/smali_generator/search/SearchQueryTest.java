package com.smali_generator.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class SearchQueryTest {

    @Test
    public void takesTheTypedWordsBackOutOfTheExpression() {
        assertEquals(Collections.singletonList("74164"), SearchQuery.tokensOf("content:74164*"));
        assertEquals(Arrays.asList("dad", "call"), SearchQuery.tokensOf("content:dad content:call*"));
    }

    @Test
    public void ignoresTermsThatArePartOfWhatsAppsOwnJidResolution() {
        assertEquals(Collections.singletonList("74164"),
                SearchQuery.tokensOf("content:74164* OR fts_jid:2s OR fts_jid:9x"));
    }

    @Test
    public void aQuotedQueryCarriesNoContentTerms() {
        assertTrue(SearchQuery.tokensOf("\"exact phrase\"").isEmpty());
        assertTrue(SearchQuery.tokensOf("").isEmpty());
        assertTrue(SearchQuery.tokensOf(null).isEmpty());
    }

    @Test
    public void tellsChatScopingApartFromContactResolution() {
        // The quoted, space-after-colon form is written only when the search is
        // scoped to one chat. Appending an OR'd term there would leak other
        // chats into an in-chat search.
        assertTrue(SearchQuery.isChatScoped("content:a fts_jid: \"0 2s\" OR fts_jid: \"1 2s\""));
        assertFalse(SearchQuery.isChatScoped("content:a OR fts_jid:2s"));
        assertFalse(SearchQuery.isChatScoped(null));
    }

    @Test
    public void readsAPhoneNumberThroughItsSeparators() {
        assertEquals("9726574164", SearchQuery.digitsOf("+972-65 74164"));
        assertEquals("74164", SearchQuery.digitsOf("74164"));
        assertEquals("", SearchQuery.digitsOf("dad"));
        assertEquals("", SearchQuery.digitsOf("74164a"));
        assertEquals("", SearchQuery.digitsOf(""));
    }

    @Test
    public void namesARowTheWayTheIndexDoes() {
        assertEquals("a", SearchQuery.token(0L, 10, 36));
        assertEquals("b", SearchQuery.token(1L, 10, 36));
        assertEquals("10", SearchQuery.token(26L, 10, 36));
        assertEquals("0", SearchQuery.token(0L, 0, 36));
    }

    @Test
    public void assemblesTermsThatOrOntoTheEndOfAnExpression() {
        assertEquals(" OR fts_jid:b OR fts_jid:c", SearchQuery.orTerms(Arrays.asList(1L, 2L), 10, 36));
        assertEquals("", SearchQuery.orTerms(Collections.<Long>emptyList(), 10, 36));
    }
}
