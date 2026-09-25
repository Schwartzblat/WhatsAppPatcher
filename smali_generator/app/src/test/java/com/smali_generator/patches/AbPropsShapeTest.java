package com.smali_generator.patches;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * The funnel hands the app an override only in the shape the app's own answer
 * came in, because the accessor that asked casts to that shape. Getting it
 * wrong is a ClassCastException inside WhatsApp's code, so every mismatch here
 * has to come back null -- "leave the app's answer alone".
 *
 * The JSON branch parses with org.json, which is a throwing stub on the JVM;
 * it is covered on the device instead.
 */
public class AbPropsShapeTest {

    @Test
    public void a_boolean_override_answers_a_boolean_read() {
        assertSame(Boolean.TRUE, AbProps.shape(1, Boolean.TRUE, Boolean.FALSE));
    }

    @Test
    public void any_number_answers_any_number_read() {
        // The int and float accessors both cast the funnel's answer to Number.
        assertEquals(7, AbProps.shape(1, 7, 3L));
        assertEquals(0.5f, AbProps.shape(1, 0.5f, 2));
    }

    @Test
    public void text_answers_a_string_read() {
        assertEquals("on", AbProps.shape(1, "on", "off"));
    }

    @Test
    public void a_mismatched_override_leaves_the_app_alone() {
        // A stored override outlives the release that gave the property its type.
        assertNull(AbProps.shape(1, Boolean.TRUE, 3));
        assertNull(AbProps.shape(1, 3, Boolean.FALSE));
        assertNull(AbProps.shape(1, "true", Boolean.FALSE));
        assertNull(AbProps.shape(1, 3, "3"));
    }

    @Test
    public void no_answer_to_go_by_leaves_the_app_alone() {
        // Text is a String to one accessor and a JSONObject to another, and
        // with the app's own answer null there is no telling which asked.
        assertNull(AbProps.shape(1, "{}", null));
        assertNull(AbProps.shape(1, Boolean.TRUE, null));
    }

    @Test
    public void an_override_that_did_not_parse_leaves_the_app_alone() {
        assertNull(AbProps.shape(1, null, Boolean.FALSE));
    }
}
