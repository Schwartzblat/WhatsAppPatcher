package com.smali_generator;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The obfuscated names the phone found for this WhatsApp version, read from
 * assets/patcher/artifactory.json (a flat string -> string object).
 *
 * Total: a missing or corrupt file is an empty map, and an empty value counts
 * as missing, so a hook asks {@link #hasAll} rather than guessing.
 */
public final class Artifacts {

    public static final String ASSET = "patcher/artifactory.json";

    private static Map<String, String> values = Collections.emptyMap();

    private Artifacts() {}

    public static synchronized void init(Context context) {
        String json = null;
        try (InputStream in = context.getAssets().open(ASSET)) {
            json = readAll(in);
        } catch (Throwable t) {
            Log.w("PATCH", "Artifacts: could not read " + ASSET, t);
        }
        load(json);
    }

    static synchronized void load(String json) {
        Map<String, String> parsed = new HashMap<>();
        try {
            if (json != null) {
                JSONObject object = new JSONObject(json);
                Iterator<String> keys = object.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    parsed.put(key, object.optString(key, ""));
                }
            }
        } catch (Throwable t) {
            parsed.clear();
        }
        values = parsed;
    }

    /** The value for {@code key}, or null when absent or empty. */
    public static synchronized String get(String key) {
        String value = values.get(key);
        return value == null || value.isEmpty() ? null : value;
    }

    public static synchronized boolean hasAll(Collection<String> keys) {
        for (String key : keys) {
            if (get(key) == null) {
                return false;
            }
        }
        return true;
    }

    static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
