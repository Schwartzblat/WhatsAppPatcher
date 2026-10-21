package com.smali_generator;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

public class FeaturesTest {
    private static final String SAMPLE = "{\"required_keys\":[\"S\"],\"official_cert_sha256\":[],"
            + "\"features\":[{\"id\":\"x\",\"title\":\"X\",\"keys\":[\"K\"]}]}";

    @Test public void keysForKnownAndUnknownIds() {
        Features.load(SAMPLE);
        assertEquals(Arrays.asList("K"), Features.keysFor("x"));
        assertTrue(Features.keysFor("nope").isEmpty());
    }
    @Test public void hookAvailabilityFollowsItsKeys() {
        Features.load(SAMPLE);
        Hook hook = new Hook() { public void load() {} public void unload() {} public String id() { return "x"; } };
        Artifacts.load("{}"); assertFalse(hook.isAvailable());
        Artifacts.load("{\"K\":\"v\"}"); assertTrue(hook.isAvailable());
    }
    @Test public void shippedFeaturesFileParses() throws Exception {
        Features.load(new String(Files.readAllBytes(Paths.get("src/main/assets/patcher/features.json")), UTF_8));
        assertEquals(Arrays.asList("PACKAGE_SIGNATURE", "FMESSAGE_CLASS", "SETTINGS_ACTIVITY_CLASS_NAME"), Features.requiredKeys());
        assertFalse(Features.keysFor("delete_for_everyone").isEmpty());
    }
}
