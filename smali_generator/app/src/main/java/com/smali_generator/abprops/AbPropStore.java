package com.smali_generator.abprops;

import android.util.Log;
import android.util.SparseArray;

import com.smali_generator.db.PatchDb;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The overrides the accessors answer with, and what they answered without one.
 *
 * Read from the accessor path, which on 2.26.36.71 is reachable from 13,336
 * call sites, so the lookup has to be cheap: the overrides live in one
 * immutable {@link SparseArray} behind a volatile field, replaced whole when
 * the screen edits one. Nothing overridden means the field is null and the
 * accessor path costs a single volatile read.
 *
 * Every method is total. A property read is not a place to throw: it happens
 * while the app is deciding whether a feature exists, on whatever thread got
 * there first.
 */
public final class AbPropStore {

    private static final String TAG = "PATCH";

    /** An override as the screen holds it: the text that was typed, and what
     *  that text means once read as the property's type. */
    public static final class Override {
        public final AbProp.Type type;
        public final String text;

        /** Null when {@link #text} is not a value of {@link #type}, which is
         *  how a bad one stays out of the app rather than reaching an accessor. */
        public final Object value;

        /**
         * Set the first time an accessor answers with this override.
         *
         * It is the only sign from outside that an override is reaching the
         * app at all: a property the app never asks about takes the value it
         * is given and changes nothing, and that is indistinguishable from a
         * hook that is not working.
         */
        public volatile boolean served;

        /** Remembered but not installed, because a launch failed. */
        public final boolean heldBack;

        /** Consumed the moment it is installed, so it applies to this run only. */
        public final boolean once;

        /**
         * The feature that asked for this value, or null when somebody typed it.
         *
         * The screen lists a property's live value and what is forcing it, and
         * a feature override is invisible to everything else it could read: the
         * observation is deliberately the app's own answer, so without this a
         * row would show the shipped default and no override while the app was
         * being told something else.
         */
        public final String source;

        Override(AbProp.Type type, String text, boolean heldBack, boolean once) {
            this(type, text, heldBack, once, null);
        }

        Override(AbProp.Type type, String text, boolean heldBack, boolean once, String source) {
            this.type = type;
            this.text = text;
            this.value = type.parse(text);
            this.heldBack = heldBack;
            this.once = once;
            this.source = source;
        }
    }

    /** Keyed by id, so it is also what the screen lists. */
    private static final Map<Integer, Override> overrides = new ConcurrentHashMap<>();

    /**
     * The same overrides in the form the accessor path reads, keyed by id.
     * Null rather than empty: an empty SparseArray would still cost a search.
     *
     * It holds the {@link Override} rather than its value so that an accessor
     * can mark one as answered with a single field write -- no boxing and no
     * hashing on a path the app reaches from 13,336 call sites.
     */
    private static volatile SparseArray<Override> installed;

    /**
     * The overrides a feature hook asks for, layered under the manual ones.
     *
     * A feature whose whole implementation is "answer property N differently"
     * must not write to {@code ab_props}: that table is what somebody typed, and
     * a feature putting its own values there would both bury a manual override
     * of the same id and leave its own behind once it is switched off. So these
     * live apart, are consulted only where nothing manual covers the id, and are
     * rebuilt from the feature's own configuration at every launch.
     */
    private static final Map<Integer, Override> requested = new ConcurrentHashMap<>();

    /** The same, in the form the accessor path reads. Null rather than empty,
     *  for the same reason as {@link #installed}. */
    private static volatile SparseArray<Override> requestedInstalled;

    /**
     * Whether a failed launch has taken the feature layer out of service.
     *
     * In memory only, unlike the manual overrides' {@code held_back} column: the
     * layer is published by each feature's {@code load()} and so is rebuilt from
     * nothing on every launch anyway. {@link InitProvider} sets this before any
     * hook runs, which is the only moment that matters.
     */
    private static volatile boolean requestedHeldBack;

    /** What each accessor last answered of its own accord. */
    private static final Map<Integer, Object> observed = new ConcurrentHashMap<>();

    /**
     * The app's own properties object, kept from the first read.
     *
     * It is the only way to the default tables, and there is no static field
     * anywhere that hands it out; the accessor path is where it goes past.
     */
    private static volatile Object owner;

    private static final AtomicBoolean loaded = new AtomicBoolean(false);

    private AbPropStore() {
    }

    /**
     * Reads the stored overrides. Idempotent, because both the hook and the
     * settings screen need them and either may come first -- the screen is
     * reachable with the hook switched off.
     */
    public static void load() {
        if (loaded.getAndSet(true)) {
            return;
        }
        try {
            PatchDb.forEachAbProp((id, type, value, heldBack, once) -> {
                AbProp.Type parsed = AbProp.Type.byLabel(type);
                if (parsed != null) {
                    overrides.put(id, new Override(parsed, value, heldBack, once));
                }
            });
            install();
            consumeOnce();
            Log.i(TAG, "AbPropStore: " + overrides.size() + " override(s) loaded, "
                    + heldBackCount() + " held back");
        } catch (Throwable t) {
            Log.e(TAG, "AbPropStore: could not read the overrides", t);
        }
    }

    /**
     * Forgets the overrides that were only meant for this launch, now that
     * they have been installed.
     *
     * Deleted here, at the start, rather than on the way out: the point of one
     * is that the run being watched has it and the next one does not, whatever
     * happens in between -- and "whatever happens in between" is exactly the
     * case where nothing gets to run on the way out. One that is held back was
     * never installed, so it is left alone.
     */
    private static void consumeOnce() {
        for (Map.Entry<Integer, Override> entry : overrides.entrySet()) {
            Override override = entry.getValue();
            if (override.once && !override.heldBack) {
                PatchDb.putAbProp(entry.getKey(), null, null, false);
                Log.i(TAG, "AbPropStore: " + entry.getKey() + " is set for this launch only");
            }
        }
    }

    /**
     * The override for this property, or null when it has none.
     *
     * The hot path. Callers check the type of {@link Override#value} rather
     * than trusting it: an id reaches one accessor only, but a stored override
     * outlives the release that gave the property its type.
     */
    public static Override override(int id) {
        SparseArray<Override> current = installed;
        if (current != null) {
            Override manual = current.get(id);
            if (manual != null) {
                return manual;
            }
        }
        // Second, so that a property somebody has set by hand keeps the value
        // they set whatever a feature would like it to be. With neither layer in
        // use this costs two volatile reads and no search.
        SparseArray<Override> features = requestedInstalled;
        return features == null ? null : features.get(id);
    }

    /**
     * Notes what an accessor answered, and keeps the properties object it was
     * asked of.
     *
     * Called with the app's own answer on every read, overridden or not --
     * the hook asks the app first and substitutes after -- so an overridden
     * property keeps showing what the app itself says rather than this
     * patch's own answer read back.
     */
    public static void observe(Object from, int id, Object value) {
        if (owner == null && from != null) {
            owner = from;
        }
        if (value == null) {
            return;
        }
        Integer key = id;
        Object previous = observed.get(key);
        if (previous == null || !previous.equals(value)) {
            observed.put(key, value);
        }
    }

    /** The app's properties object, or null if nothing has read a property yet
     *  -- which in practice means the hook is switched off. */
    public static Object owner() {
        return owner;
    }

    public static Map<Integer, Override> overrides() {
        return overrides;
    }

    /** How many overrides are actually in force. A held-back one is stored but
     *  is not changing any answer, so it does not count as one. */
    public static int count() {
        int count = 0;
        for (Override override : overrides.values()) {
            if (!override.heldBack) {
                count++;
            }
        }
        return count;
    }

    /**
     * Overrides a property, or stops overriding it when {@code text} is null.
     *
     * Stored before it is installed, so a value that survives a restart is
     * exactly the one the accessors are now answering with.
     */
    public static void set(int id, AbProp.Type type, String text, boolean once) {
        if (text == null || type == null) {
            overrides.remove(id);
            PatchDb.putAbProp(id, null, null, false);
        } else {
            // Never held back: setting one by hand is also how a held-back
            // override is put back to work.
            overrides.put(id, new Override(type, text, false, once));
            PatchDb.putAbProp(id, type.label(), text, once);
        }
        install();
    }

    /**
     * Asks that a property be answered with {@code text} for as long as this
     * process lives, on behalf of {@code source}.
     *
     * Null text stops asking. Nothing is stored: the feature's own settings are
     * what survives a restart, and they are read again the next time it loads.
     * Safe to call with no funnel hooked -- then nothing reads this and the
     * feature simply does nothing, which is what a switched-off A/B hook means.
     */
    public static void feature(int id, AbProp.Type type, String text, String source) {
        if (text == null || type == null) {
            requested.remove(id);
        } else {
            requested.put(id, new Override(type, text, requestedHeldBack, false, source));
        }
        installRequested();
    }

    /** The feature layer's override for this property, for a screen that wants
     *  to say what is forcing it. Not the accessor path; that uses
     *  {@link #override(int)}. */
    public static Override featureOverride(int id) {
        return requested.get(id);
    }

    public static void clearAll() {
        overrides.clear();
        PatchDb.clearAbProps();
        install();
    }

    /**
     * Takes every override out of service, keeping it.
     *
     * Called before anything reads the store, by the launch after a failed
     * one. Storage first, so that a store loaded afterwards reads them as held
     * back rather than installing them and having them taken away again.
     */
    public static void holdBackAll() {
        PatchDb.setAbPropsHeldBack(true);
        // Set before the features publish rather than after: a launch that is
        // already suspect must never have their values installed even briefly.
        requestedHeldBack = true;
        rebuildRequested();
        if (loaded.get()) {
            reread();
        }
    }

    /** Puts every held-back override back to work. Takes effect on the next
     *  read; the app has cached what it was told in the meantime. */
    public static void restoreHeldBack() {
        PatchDb.setAbPropsHeldBack(false);
        requestedHeldBack = false;
        rebuildRequested();
        reread();
    }

    private static void reread() {
        overrides.clear();
        loaded.set(false);
        load();
    }

    /** Every override the screen is holding, in force or not -- which is what
     *  "clear all" acts on. */
    public static int storedCount() {
        return overrides.size();
    }

    public static int heldBackCount() {
        int count = 0;
        for (Override override : overrides.values()) {
            if (override.heldBack) {
                count++;
            }
        }
        return count;
    }

    /** Rebuilt whole rather than edited: the accessor path reads it without a
     *  lock, so it must never be seen half-changed. */
    private static void install() {
        if (overrides.isEmpty()) {
            installed = null;
            return;
        }
        SparseArray<Override> next = new SparseArray<>(overrides.size());
        for (Map.Entry<Integer, Override> entry : overrides.entrySet()) {
            if (entry.getValue().value != null && !entry.getValue().heldBack) {
                next.put(entry.getKey(), entry.getValue());
            }
        }
        installed = next.size() == 0 ? null : next;
    }

    /** Rebuilt whole, like {@link #install()}, and for the same reason. */
    private static void installRequested() {
        if (requested.isEmpty()) {
            requestedInstalled = null;
            return;
        }
        SparseArray<Override> next = new SparseArray<>(requested.size());
        for (Map.Entry<Integer, Override> entry : requested.entrySet()) {
            if (entry.getValue().value != null && !entry.getValue().heldBack) {
                next.put(entry.getKey(), entry.getValue());
            }
        }
        requestedInstalled = next.size() == 0 ? null : next;
    }

    /** Re-makes the feature layer's entries against the current hold-back state,
     *  keeping what each feature asked for. */
    private static void rebuildRequested() {
        for (Map.Entry<Integer, Override> entry : requested.entrySet()) {
            Override asked = entry.getValue();
            entry.setValue(new Override(asked.type, asked.text, requestedHeldBack, false, asked.source));
        }
        installRequested();
    }

    /** How many feature overrides are in force, for the screen's own count. */
    public static int featureCount() {
        SparseArray<Override> current = requestedInstalled;
        return current == null ? 0 : current.size();
    }

    /**
     * Every property the app has been seen to read, this run and earlier ones.
     *
     * This run wins: an earlier run's value can predate a server config change,
     * or the override that is now hiding it.
     */
    public static Map<Integer, Object> observations() {
        Map<Integer, Object> merged = new HashMap<>();
        try {
            PatchDb.forEachAbPropSeen((id, type, value) -> {
                AbProp.Type parsed = AbProp.Type.byLabel(type);
                Object restored = parsed == null ? null : parsed.parse(value);
                if (restored != null) {
                    merged.put(id, restored);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "AbPropStore: could not read what was seen before", t);
        }
        merged.putAll(observed);
        return merged;
    }

    /**
     * Writes this run's observations out, so that the list of properties the
     * app actually reads survives the restart an override needs anyway.
     *
     * Called when the screen opens rather than as properties are read: the
     * accessor path does not touch storage.
     */
    public static void flushObservations() {
        if (observed.isEmpty()) {
            return;
        }
        PatchDb.saveAbPropsSeen(visitor -> {
            for (Map.Entry<Integer, Object> entry : observed.entrySet()) {
                AbProp.Type type = AbProp.Type.of(entry.getValue());
                if (type != null) {
                    visitor.visit(entry.getKey(), type.label(), String.valueOf(entry.getValue()));
                }
            }
        });
        Log.i(TAG, "AbPropStore: " + observed.size() + " property read(s) recorded");
    }
}
