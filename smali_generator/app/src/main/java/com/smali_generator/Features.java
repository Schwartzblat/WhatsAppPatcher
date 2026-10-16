package com.smali_generator;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which artifact keys each hook needs, from assets/patcher/features.json.
 * The same file is read by the patcher side, so it is the one source of truth.
 *
 * Total: a missing or corrupt file means no hook has keys, which
 * {@link Hook#isAvailable} reads as "available" -- the required keys are what
 * the patcher checks before it ever ships such a build.
 */
public final class Features {

    public static final String ASSET = "patcher/features.json";

    private static Map<String, List<String>> keysById = Collections.emptyMap();
    private static List<String> required = Collections.emptyList();

    private Features() {}

    public static synchronized void init(Context context) {
        String json = null;
        try (InputStream in = context.getAssets().open(ASSET)) {
            json = Artifacts.readAll(in);
        } catch (Throwable t) {
            Log.w("PATCH", "Features: could not read " + ASSET, t);
        }
        load(json);
    }

    static synchronized void load(String json) {
        Map<String, List<String>> parsed = new HashMap<>();
        List<String> parsedRequired = new ArrayList<>();
        try {
            if (json != null) {
                JSONObject root = new JSONObject(json);
                parsedRequired = strings(root.optJSONArray("required_keys"));
                JSONArray features = root.optJSONArray("features");
                for (int i = 0; features != null && i < features.length(); i++) {
                    JSONObject feature = features.getJSONObject(i);
                    parsed.put(feature.getString("id"), strings(feature.optJSONArray("keys")));
                }
            }
        } catch (Throwable t) {
            parsed.clear();
            parsedRequired = new ArrayList<>();
        }
        keysById = parsed;
        required = parsedRequired;
    }

    public static synchronized List<String> keysFor(String hookId) {
        List<String> keys = keysById.get(hookId);
        return keys == null ? Collections.<String>emptyList() : keys;
    }

    public static synchronized List<String> requiredKeys() {
        return required;
    }

    private static List<String> strings(JSONArray array) throws org.json.JSONException {
        List<String> out = new ArrayList<>();
        for (int i = 0; array != null && i < array.length(); i++) {
            out.add(array.getString(i));
        }
        return Collections.unmodifiableList(out);
    }
}
