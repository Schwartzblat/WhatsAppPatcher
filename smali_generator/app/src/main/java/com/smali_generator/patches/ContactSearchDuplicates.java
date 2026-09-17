package com.smali_generator.patches;

import android.util.Log;

import com.arthooks.ArtHooks;

import com.smali_generator.Hook;
import com.smali_generator.HookCategory;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists a person once in search, however many rows WhatsApp holds for them.
 *
 * {@code wa_contacts} has no unique constraint on the jid, and rows for one
 * person accumulate there: some come from the address book, one per raw
 * contact, and the rest WhatsApp writes for itself with a negative
 * {@code raw_contact_id}. Measured on the device this was reported from:
 * 41,305 rows for 39,650 jids, 237 people with more than one row, the worst
 * with nine -- seven of them WhatsApp's own, all {@code raw_contact_id = -5},
 * with consecutive row ids.
 *
 * The contact index is keyed on {@code wa_contacts._id}, so every one of those
 * rows is a document of its own and comes back as its own result: 500 rows for
 * 320 people on one query, and the same contact listed nine times on screen.
 * Nothing between that search and the screen collapses them, so this does, on
 * the one list every contact result passes through.
 *
 * Which row survives is not arbitrary. A row the address book is behind can
 * still open a contact card and carries the number's type and label; the rows
 * WhatsApp wrote for itself carry none of that, and they sort first, because
 * the search orders by {@code phone_type} and theirs is 0. So a duplicate with
 * a real raw contact id replaces one without, in the place the first one held.
 *
 * It cannot add or reorder results: it removes rows naming a person already in
 * the list, in the app's own list, and a row it cannot key on is kept. If
 * anything at all goes wrong the list is left exactly as WhatsApp built it.
 */
public class ContactSearchDuplicates implements Hook {

    private static final String TAG = "PATCH";

    /** Contact.getJid(Class), what two rows for one person agree on. */
    private static volatile Method contactJid;
    /** Contact.getRawContactId(), negative on the rows WhatsApp wrote for itself. */
    private static volatile Method contactRawId;
    /** The type the jid getter narrows on -- Jid itself, so it answers for every contact. */
    private static volatile Class<?> anyJid;

    /**
     * One-shot, and two of them: the search runs on every keystroke, so a line
     * per call would be noise, and a query that had nothing to collapse would
     * otherwise look exactly like a hook that never ran.
     */
    private static volatile boolean loggedCall;
    private static volatile boolean loggedCollapse;

    /** native on purpose: a body here would be inlined into the hook and the backup would
     * silently answer for the original. ArtHooks rewrites its entry point. */
    static native Object search_backup(Object thiz, Object query, int limit);

    static Object search_hook(Object thiz, Object query, int limit) {
        Object answer = search_backup(thiz, query, limit);
        try {
            collapse(answer);
        } catch (Throwable t) {
            // On the search worker, with the user waiting on the query: the
            // app's own answer is always a valid one, duplicates and all.
            Log.e(TAG, "ContactSearchDuplicates: the results were left as WhatsApp built them", t);
        }
        // The app's own answer object either way; only its contents changed.
        return answer;
    }

    private static void collapse(Object answer) throws Exception {
        Method jidOf = contactJid;
        if (jidOf == null) {
            return;
        }
        List results = resultsIn(answer);
        if (results == null || results.size() < 2) {
            return;
        }

        Map<Object, Integer> firstAt = new HashMap<Object, Integer>(results.size() * 2);
        List<Object> once = new ArrayList<Object>(results.size());
        for (Object contact : results) {
            Object jid = contact == null ? null : jidOf.invoke(contact, anyJid);
            if (jid == null) {
                // A row naming nobody has nothing to collapse onto, and
                // dropping it would lose a result rather than a duplicate.
                once.add(contact);
                continue;
            }
            Integer at = firstAt.get(jid);
            if (at == null) {
                firstAt.put(jid, Integer.valueOf(once.size()));
                once.add(contact);
            } else if (fromAddressBook(contact) && !fromAddressBook(once.get(at.intValue()))) {
                once.set(at.intValue(), contact);
            }
        }

        int removed = results.size() - once.size();
        if (!loggedCall) {
            loggedCall = true;
            Log.i(TAG, "ContactSearchDuplicates: contact search returned " + results.size()
                    + " row(s) for " + once.size() + " person(s)");
        }
        if (removed <= 0) {
            return;
        }
        // In place, because the answer this list sits inside is the app's and
        // rebuilding it would mean knowing which of three wrappers it is. An
        // unmodifiable list throws here and the results stay untouched.
        results.clear();
        results.addAll(once);
        if (!loggedCollapse) {
            loggedCollapse = true;
            Log.i(TAG, "ContactSearchDuplicates: dropped " + removed
                    + " duplicate row(s), " + once.size() + " contact(s) left");
        }
    }

    /**
     * The rows inside WhatsApp's own answer, or null when it carried none.
     *
     * The answer is one of three wrappers -- found, nothing, failed -- and only
     * one of them holds a list. Read by shape rather than by name: which field
     * holds it is renamed every release, being a list is not.
     */
    private static List resultsIn(Object answer) throws IllegalAccessException {
        if (answer == null) {
            return null;
        }
        Field[] fields = answer.getClass().getDeclaredFields();
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(answer);
            if (value instanceof List) {
                return (List) value;
            }
        }
        return null;
    }

    /**
     * Whether this row is one the address book is behind.
     *
     * False is the safe answer: it only ever means "no reason to prefer this
     * one over the row already kept", so a build that moved the getter costs
     * the preference, not the de-dupe.
     */
    private static boolean fromAddressBook(Object contact) {
        Method rawIdOf = contactRawId;
        if (rawIdOf == null || contact == null) {
            return false;
        }
        try {
            Object rawId = rawIdOf.invoke(contact);
            return rawId instanceof Long && ((Long) rawId).longValue() >= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public String id() {
        return "contact_search_duplicates";
    }

    public String title() {
        return "Show each contact once in search";
    }

    public String description() {
        return "WhatsApp keeps several contact rows for the same person and lists one result per row. "
                + "This shows each person once.";
    }

    public HookCategory category() {
        return HookCategory.SEARCH;
    }

    public void load() {
        try {
            // Unobfuscated, and the same class the finder's signature names, so
            // this literal cannot be wrong while that finder fires.
            anyJid = Class.forName("com.whatsapp.infra.core.jid.Jid");
            Class<?> contact = Class.forName("{{CONTACT_JID_CLASS_NAME}}");
            contactJid = contact.getMethod("{{CONTACT_JID_METHOD_NAME}}", Class.class);
            try {
                contactRawId = contact.getMethod("{{CONTACT_RAW_ID_METHOD_NAME}}");
            } catch (Throwable t) {
                // Only the preference is lost: without it the first row for a
                // person is the one kept.
                Log.e(TAG, "ContactSearchDuplicates: the address-book row cannot be told apart: " + t);
            }

            Class<?> owner = Class.forName("{{CONTACT_SEARCH_CLASS_NAME}}");
            Executable search = ArtHooks.find_function(owner,
                    "{{CONTACT_SEARCH_METHOD_NAME}}", "{{CONTACT_SEARCH_METHOD_SIG}}");
            if (Modifier.isStatic(search.getModifiers())) {
                // The replacement's leading parameter is the receiver, so a
                // staticized method would read the query out of the slot the
                // receiver is meant to occupy. Hooking the static method this
                // one calls recursed into the replacement and took the app
                // down on the first search; declining is the cheap half of that
                // lesson.
                Log.e(TAG, "ContactSearchDuplicates: the contact search is static on this build,"
                        + " duplicates were left in place");
                return;
            }
            Method replacement = ContactSearchDuplicates.class.getDeclaredMethod("search_hook",
                    Object.class, Object.class, int.class);
            Method original = ContactSearchDuplicates.class.getDeclaredMethod("search_backup",
                    Object.class, Object.class, int.class);
            boolean hooked = ArtHooks.hook_function(search, replacement, original);
            if (!hooked) {
                // false means the search runs unmodified with no exception
                // anywhere -- the same silence as success.
                Log.e(TAG, "ContactSearchDuplicates: hook_function returned false,"
                        + " duplicates were left in place");
            }

            Log.i(TAG, "ContactSearchDuplicates: hooked on " + owner.getName()
                    + ".{{CONTACT_SEARCH_METHOD_NAME}}, jid from " + contact.getName()
                    + ".{{CONTACT_JID_METHOD_NAME}}, hooked=" + hooked);
        } catch (Throwable t) {
            Log.e(TAG, "ContactSearchDuplicates: duplicates were left in place: " + t);
        }
    }

    public void unload() {
        Log.i(TAG, "ContactSearchDuplicates: Patch unloaded");
    }
}
