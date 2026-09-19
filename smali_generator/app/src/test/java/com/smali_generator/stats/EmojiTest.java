package com.smali_generator.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class EmojiTest {

    private Map<String, Long> tally(String text) {
        Map<String, Long> into = new HashMap<>();
        Emoji.tally(text, into);
        return into;
    }

    @Test
    public void plain_text_has_no_emoji() {
        assertTrue(tally("just some words, 123!").isEmpty());
    }

    @Test
    public void counts_a_single_emoji() {
        assertEquals(Long.valueOf(1), tally("hello 😀").get("😀"));
    }

    @Test
    public void counts_repeats() {
        assertEquals(Long.valueOf(3), tally("😀😀😀")
                .get("😀"));
    }

    @Test
    public void a_skin_tone_does_not_become_its_own_emoji() {
        // Thumbs up + medium skin tone modifier: one gesture, not two symbols.
        Map<String, Long> counts = tally("👍🏽");
        assertEquals(1, counts.size());
        assertEquals(Long.valueOf(1), counts.get("👍"));
    }

    @Test
    public void a_zwj_sequence_counts_once() {
        // Man + ZWJ + woman + ZWJ + girl is one family, not three people.
        Map<String, Long> counts = tally("👨‍👩‍👧");
        assertEquals(1, counts.size());
        assertEquals(Long.valueOf(1), counts.get("👨"));
    }

    @Test
    public void a_variation_selector_is_not_counted() {
        Map<String, Long> counts = tally("❤️");
        assertEquals(1, counts.size());
        assertEquals(Long.valueOf(1), counts.get("❤"));
    }

    @Test
    public void a_flag_is_one_emoji_not_two_letters() {
        // Regional indicators only mean anything in pairs.
        Map<String, Long> counts = tally("🇮🇱");
        assertEquals(1, counts.size());
        assertEquals(Long.valueOf(1), counts.get("🇮🇱"));
    }

    @Test
    public void handles_null_and_empty() {
        assertTrue(tally("").isEmpty());
        Map<String, Long> into = new HashMap<>();
        Emoji.tally(null, into);
        assertTrue(into.isEmpty());
    }
}
