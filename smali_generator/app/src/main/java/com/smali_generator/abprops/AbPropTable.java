package com.smali_generator.abprops;

import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every property the build ships a default for, read out of the app itself.
 *
 * The defaults are not extracted at patch time and pasted in. The properties
 * object carries them: five tables of {id -> default}, one per value type,
 * handed out by five no-argument methods whose names move with every release.
 * Nothing here knows those names -- it asks the object for the methods that
 * return a map of that kind and calls them, which is a shape no rename can
 * touch. Each value's own class then says what type the property is.
 *
 * 22,088 properties on 2.26.36.71. Built once, when the screen opens.
 */
public final class AbPropTable {

    private static final String TAG = "PATCH";

    /**
     * Guava's immutable map, spelled out.
     *
     * A library type, so it keeps its name where everything around it loses
     * one, and matching on it exactly is what keeps this from calling some
     * unrelated no-argument method for its side effects.
     */
    private static final String TABLE_TYPE = "com.google.common.collect.ImmutableMap";

    private AbPropTable() {
    }

    /**
     * The app's own properties, in id order, with anything the app has been
     * seen to read but no table holds added on.
     *
     * Empty when {@code props} is null, which is the state of a process where
     * nothing has read a property -- in practice, one where the hook is off.
     */
    public static List<AbProp> load(Object props, Map<Integer, Object> observed) {
        Map<Integer, AbProp> byId = new LinkedHashMap<>();
        if (props != null) {
            for (Method table : tables(props.getClass())) {
                collect(props, table, byId);
            }
            Log.i(TAG, "AbPropTable: " + byId.size() + " propert(ies) with a shipped default");
        }
        // A property the app asked for that no table holds. It should not
        // happen -- an unknown id is what the accessors throw over -- but if it
        // does, the id the app is actually using is worth more than the tables.
        if (observed != null) {
            for (Map.Entry<Integer, Object> entry : observed.entrySet()) {
                if (byId.containsKey(entry.getKey())) {
                    continue;
                }
                AbProp.Type type = AbProp.Type.of(entry.getValue());
                if (type != null) {
                    byId.put(entry.getKey(), new AbProp(entry.getKey(), type, null));
                }
            }
        }
        List<AbProp> sorted = new ArrayList<>(byId.values());
        Collections.sort(sorted, (left, right) -> Integer.compare(left.id, right.id));
        return sorted;
    }

    private static List<Method> tables(Class<?> owner) {
        List<Method> found = new ArrayList<>();
        for (Method method : owner.getMethods()) {
            if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (method.isSynthetic() || method.isBridge()) {
                continue;
            }
            if (TABLE_TYPE.equals(method.getReturnType().getName())) {
                found.add(method);
            }
        }
        return found;
    }

    /**
     * One table's worth, guarded on its own: a release that adds a sixth table
     * of some type this cannot read should still yield the five it can.
     */
    private static void collect(Object props, Method table, Map<Integer, AbProp> into) {
        try {
            Object value = table.invoke(props);
            if (!(value instanceof Map)) {
                return;
            }
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof Integer)) {
                    continue;
                }
                AbProp.Type type = AbProp.Type.of(entry.getValue());
                int id = (Integer) entry.getKey();
                // First table wins. The five are disjoint, but a subclass can
                // expose the same map twice -- once as its own method, once as
                // the one it overrides -- and a property must not be listed
                // twice.
                if (type != null && !into.containsKey(id)) {
                    into.put(id, new AbProp(id, type, entry.getValue()));
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "AbPropTable: " + table.getName() + " could not be read", t);
        }
    }
}
