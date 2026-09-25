package com.smali_generator.abprops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * "Changed from the shipped default" is decided by this, and the two sides box
 * the same number differently: a Long in the default table against whatever
 * the app's own answer was boxed as.
 */
public class AbPropSameValueTest {

    @Test
    public void the_same_number_boxed_differently_is_the_same_value() {
        assertTrue(AbProp.sameValue(5L, 5));
        assertTrue(AbProp.sameValue(0.1f, 0.1d));
    }

    @Test
    public void different_numbers_differ() {
        assertFalse(AbProp.sameValue(512, 1024L));
        assertFalse(AbProp.sameValue(0.5f, 0.25d));
    }

    @Test
    public void everything_else_compares_by_equals() {
        assertTrue(AbProp.sameValue(Boolean.TRUE, Boolean.TRUE));
        assertFalse(AbProp.sameValue(Boolean.TRUE, Boolean.FALSE));
        assertTrue(AbProp.sameValue("a", "a"));
        assertFalse(AbProp.sameValue("5", 5));
        assertTrue(AbProp.sameValue(null, null));
        assertFalse(AbProp.sameValue(null, 5));
    }
}
