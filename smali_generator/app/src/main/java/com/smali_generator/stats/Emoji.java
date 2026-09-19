package com.smali_generator.stats;

import java.util.Map;

/**
 * Counting emoji in a message, by codepoint.
 *
 * Deliberately not a grapheme segmenter. What the screen wants is "which
 * symbols does this person reach for", so a thumbs-up is a thumbs-up whatever
 * skin tone it carries, and a family is one family rather than three people.
 * That means the modifiers -- variation selectors, skin tones, keycaps -- are
 * skipped, and a ZWJ sequence collapses to the symbol it starts with.
 *
 * Flags are the exception that has to be handled rather than skipped: a
 * regional indicator on its own is a letter, and only a pair is a flag, so
 * pairs are counted whole.
 */
public final class Emoji {

    private static final int ZWJ = 0x200D;
    private static final int VARIATION_SELECTOR = 0xFE0F;
    private static final int VARIATION_SELECTOR_TEXT = 0xFE0E;
    private static final int COMBINING_KEYCAP = 0x20E3;

    private static final int SKIN_TONE_FIRST = 0x1F3FB;
    private static final int SKIN_TONE_LAST = 0x1F3FF;

    private static final int REGIONAL_FIRST = 0x1F1E6;
    private static final int REGIONAL_LAST = 0x1F1FF;

    private Emoji() {
    }

    /** Adds every emoji in {@code text} to {@code into}. */
    public static void tally(String text, Map<String, Long> into) {
        if (text == null || text.isEmpty()) {
            return;
        }
        boolean skipNext = false;
        int at = 0;
        while (at < text.length()) {
            int codePoint = text.codePointAt(at);
            at += Character.charCount(codePoint);

            if (codePoint == ZWJ) {
                // Whatever follows is part of the symbol just counted.
                skipNext = true;
                continue;
            }
            if (isModifier(codePoint)) {
                continue;
            }
            if (isRegional(codePoint)) {
                // A lone regional indicator is a letter; only a pair is a flag.
                if (at < text.length()) {
                    int next = text.codePointAt(at);
                    if (isRegional(next)) {
                        at += Character.charCount(next);
                        if (!skipNext) {
                            count(into, new String(Character.toChars(codePoint))
                                    + new String(Character.toChars(next)));
                        }
                    }
                }
                skipNext = false;
                continue;
            }
            if (!isEmoji(codePoint)) {
                skipNext = false;
                continue;
            }
            if (skipNext) {
                skipNext = false;
                continue;
            }
            count(into, new String(Character.toChars(codePoint)));
        }
    }

    private static void count(Map<String, Long> into, String emoji) {
        Long seen = into.get(emoji);
        into.put(emoji, seen == null ? 1L : seen + 1L);
    }

    /** Carried by a symbol rather than being one. */
    private static boolean isModifier(int codePoint) {
        return codePoint == VARIATION_SELECTOR
                || codePoint == VARIATION_SELECTOR_TEXT
                || codePoint == COMBINING_KEYCAP
                || (codePoint >= SKIN_TONE_FIRST && codePoint <= SKIN_TONE_LAST);
    }

    private static boolean isRegional(int codePoint) {
        return codePoint >= REGIONAL_FIRST && codePoint <= REGIONAL_LAST;
    }

    /**
     * The blocks worth counting: miscellaneous symbols and dingbats, and the
     * pictograph planes. Arrows, punctuation and the like are left out -- they
     * appear in ordinary text and would swamp the result.
     */
    private static boolean isEmoji(int codePoint) {
        if (codePoint >= 0x2600 && codePoint <= 0x27BF) {
            return true;
        }
        if (codePoint >= 0x1F300 && codePoint <= 0x1FAFF) {
            return true;
        }
        return codePoint >= 0x1F000 && codePoint <= 0x1F0FF;
    }
}
