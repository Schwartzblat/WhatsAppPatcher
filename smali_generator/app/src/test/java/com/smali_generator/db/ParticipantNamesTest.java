package com.smali_generator.db;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The rule that decides whether a stored "name" is really a number.
 *
 * Worth a test of its own because the interesting inputs are invisible: the
 * mask WhatsApp writes for an unshared number is a run of U+2219, and a number
 * rendered in a right-to-left UI carries bidi marks that no reader can see in
 * a diff.
 */
public class ParticipantNamesTest {

    @Test
    public void a_masked_number_is_a_number() {
        assertTrue(ParticipantNames.looksLikeANumber("+1 (∙∙∙) ∙∙∙ ∙∙30"));
        assertTrue(ParticipantNames.looksLikeANumber("972∙∙∙∙∙∙∙∙63"));
    }

    @Test
    public void a_plain_number_is_a_number() {
        assertTrue(ParticipantNames.looksLikeANumber("972504319431"));
        assertTrue(ParticipantNames.looksLikeANumber("+972 50-431-9431"));
    }

    @Test
    public void a_number_wrapped_in_bidi_marks_is_a_number() {
        assertTrue(ParticipantNames.looksLikeANumber("‎+972 50-431-9431‎"));
    }

    @Test
    public void a_name_is_not_a_number() {
        assertFalse(ParticipantNames.looksLikeANumber("Hallel B."));
        assertFalse(ParticipantNames.looksLikeANumber("shlomi levi"));
        assertFalse(ParticipantNames.looksLikeANumber("אלון"));
    }

    @Test
    public void a_name_carrying_digits_is_not_a_number() {
        assertFalse(ParticipantNames.looksLikeANumber("Agent 47"));
        assertFalse(ParticipantNames.looksLikeANumber("8200"));
    }

    @Test
    public void an_emoji_name_survives() {
        // Emoji are not letters, so a letters-only test would have thrown
        // these away and left the person as bare digits.
        assertFalse(ParticipantNames.looksLikeANumber("😎"));
        assertFalse(ParticipantNames.looksLikeANumber("⚜️Hallel B.🔱"));
    }

    @Test
    public void nothing_is_not_a_number() {
        assertFalse(ParticipantNames.looksLikeANumber(null));
        assertFalse(ParticipantNames.looksLikeANumber(""));
        assertFalse(ParticipantNames.looksLikeANumber("   "));
    }
}
