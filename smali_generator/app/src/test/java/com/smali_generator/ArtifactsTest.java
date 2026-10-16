package com.smali_generator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class ArtifactsTest {
    @Test public void readsValues() {
        Artifacts.load("{\"A\":\"X.abc\"}");
        assertEquals("X.abc", Artifacts.get("A")); assertNull(Artifacts.get("B"));
    }
    @Test public void emptyValueCountsAsMissing() {
        Artifacts.load("{\"A\":\"\"}");
        assertNull(Artifacts.get("A")); assertFalse(Artifacts.hasAll(Arrays.asList("A")));
    }
    @Test public void missingOrCorruptFileIsEmpty() {
        Artifacts.load(null); assertNull(Artifacts.get("A"));
        Artifacts.load("not json"); assertNull(Artifacts.get("A"));
        assertTrue(Artifacts.hasAll(Collections.<String>emptyList()));
    }
}
