package com.smali_generator.wrappers;

import android.util.Log;

import com.smali_generator.Wrapper;
import com.smali_generator.db.MessageKey;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Pulls (id, remote jid, fromMe) off an FMessage.
 *
 * Nothing here is named: the key field is found by (1) collecting all public
 * instance fields of FMessage whose type declares a String, a Jid and a
 * boolean; (2) filtering to only final fields; and (3) requiring exactly one.
 * The three fields are then found within that type by their own types. This
 * strategy avoids ambiguity: a message's identity is immutable (final), while
 * quoted message keys are mutable, so requiring finality disambiguates. All
 * discovery survives renaming; only the Jid base class is named, and it lives
 * in a package WhatsApp does not obfuscate.
 *
 * getFields() reaches public static finals on superclasses and interfaces too;
 * those are constants, never a message's key, and one of them counting as a
 * candidate would push the count past 1 and take the feature dark. Static
 * fields are therefore excluded from both scans.
 *
 * Read paths are total and quiet: from() and idOf() return null on any
 * failure, logging at most once each, so a per-row failure cannot flood the
 * log or throw into a list bind.
 */
public final class FMessageKey implements Wrapper {
    private static final String TAG = "PATCH";
    private static final String JID_CLASS = "com.whatsapp.infra.core.jid.Jid";

    private static Field keyField;
    private static Field idField;
    private static Field jidField;
    private static Field fromMeField;

    /** Read paths run once per row; a failure there logs once and then stays quiet. */
    private static boolean loggedFromFailure;
    private static boolean loggedIdOfFailure;

    private FMessageKey() {
    }

    @SuppressWarnings("unused")
    public static void init() {
        try {
            Class<?> fmessageClass = Class.forName("{{FMESSAGE_CLASS}}");
            Class<?> jidClass = Class.forName(JID_CLASS);

            // Collect all structural candidates
            List<Field> structuralCandidates = new ArrayList<>();
            for (Field candidate : fmessageClass.getFields()) {
                Class<?> type = candidate.getType();
                if (Modifier.isStatic(candidate.getModifiers())
                        || type.isPrimitive() || type.getName().startsWith("java.")) {
                    continue;
                }
                Field id = null;
                Field jid = null;
                Field fromMe = null;
                for (Field field : type.getFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (id == null && field.getType() == String.class) {
                        id = field;
                    } else if (jid == null && jidClass.isAssignableFrom(field.getType())) {
                        jid = field;
                    } else if (fromMe == null && field.getType() == boolean.class) {
                        fromMe = field;
                    }
                }
                if (id != null && jid != null && fromMe != null) {
                    structuralCandidates.add(candidate);
                }
            }

            // Filter to only final candidates
            List<Field> finalCandidates = new ArrayList<>();
            for (Field candidate : structuralCandidates) {
                if (Modifier.isFinal(candidate.getModifiers())) {
                    finalCandidates.add(candidate);
                }
            }

            // Exactly one final candidate: bind it
            if (finalCandidates.size() == 1) {
                Field candidate = finalCandidates.get(0);
                Class<?> type = candidate.getType();

                // Re-find the three fields within the final candidate type
                Field id = null;
                Field jid = null;
                Field fromMe = null;
                for (Field field : type.getFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (id == null && field.getType() == String.class) {
                        id = field;
                    } else if (jid == null && jidClass.isAssignableFrom(field.getType())) {
                        jid = field;
                    } else if (fromMe == null && field.getType() == boolean.class) {
                        fromMe = field;
                    }
                }

                candidate.setAccessible(true);
                id.setAccessible(true);
                jid.setAccessible(true);
                fromMe.setAccessible(true);
                keyField = candidate;
                idField = id;
                jidField = jid;
                fromMeField = fromMe;
                Log.i(TAG, "FMessageKey: init success, key field " + candidate.getName()
                        + " of type " + type.getName());
                return;
            }

            // Zero or more than one final candidate: log error with details
            Log.e(TAG, "FMessageKey: found " + finalCandidates.size() + " final candidates, expected 1");
            for (Field candidate : structuralCandidates) {
                boolean isFinal = Modifier.isFinal(candidate.getModifiers());
                Log.e(TAG, "  candidate: " + candidate.getName() + " of type " + candidate.getType().getName() + ", final=" + isFinal);
            }
        } catch (Throwable t) {
            Log.e(TAG, "FMessageKey: init error", t);
        }
    }

    /**
     * The full key, for the capture side. Null when the message has no
     * resolvable key; callers treat that as "not deleted".
     *
     * Prefer idOf() on the render path: building a MessageKey here calls
     * Jid.toString(), which allocates a fresh "user@server" String, and the
     * render path reads nothing but the id.
     */
    public static MessageKey from(Object fmessage) {
        if (fmessage == null || keyField == null) {
            return null;
        }
        try {
            Object key = keyField.get(fmessage);
            if (key == null) {
                return null;
            }
            Object jid = jidField.get(key);
            return new MessageKey(
                    (String) idField.get(key),
                    jid == null ? null : jid.toString(),
                    fromMeField.getBoolean(key));
        } catch (Throwable t) {
            // Once only, because callers may run per row. Without any log at all, a
            // read path that throws on every message is indistinguishable from
            // "nothing was deleted" while init still reports success.
            if (!loggedFromFailure) {
                loggedFromFailure = true;
                Log.e(TAG, "FMessageKey: from failed, key unresolvable (logged once)", t);
            }
            return null;
        }
    }

    /**
     * Just the message id, for the render path. Same contract as from(): null
     * on anything unresolvable, never throws, logs at most once.
     */
    public static String idOf(Object fmessage) {
        if (fmessage == null || keyField == null) {
            return null;
        }
        try {
            Object key = keyField.get(fmessage);
            if (key == null) {
                return null;
            }
            return (String) idField.get(key);
        } catch (Throwable t) {
            if (!loggedIdOfFailure) {
                loggedIdOfFailure = true;
                Log.e(TAG, "FMessageKey: idOf failed, indicator inert (logged once)", t);
            }
            return null;
        }
    }
}
