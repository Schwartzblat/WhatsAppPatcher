package com.smali_generator.abprops;

import android.util.Log;

import com.smali_generator.BootHealth;
import com.smali_generator.db.PatchDb;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns on every feature the app is hiding, and finds the ones that break it.
 *
 * The idea is to switch on all the boolean properties that are off, see what
 * appears, and let the app tell you which of them it cannot survive. The
 * obvious way to do that -- flip everything, then binary-search the crash -- is
 * wrong twice over. Binary search assumes one culprit and there will be many,
 * so after finding the first the other half still crashes; and a search that
 * only ever identifies bad flags ends with nothing turned on, which is the
 * opposite of the point.
 *
 * So this is a group test, not a binary search. The suspects start as one
 * block. A block that survives is promoted into a <b>safe set</b> that stays on
 * for every later trial; a block that fails is split in half and both halves go
 * back in the queue. The search therefore converges on the largest set of flags
 * that work <i>together</i>, and the safe set only ever grows -- which is what
 * makes it worth stopping half way through. Whatever has been cleared by then
 * is yours to keep.
 *
 * A trial is judged two ways, because the failure that matters most is not a
 * crash. A crash or an ANR is read off {@link BootHealth} with no input from
 * anyone. Everything else -- the blank screen, the button that does nothing,
 * the chat list that will not load -- is a judgement only a person can make,
 * and the screen asks for it.
 *
 * The flags a trial turns on are <b>not</b> written to the override table. They
 * live here and are layered under the overrides set by hand, so a hunt never
 * touches what the user chose themselves and ending one leaves no debris.
 */
public final class Hunt {

    private static final String TAG = "PATCH";

    private static final String ACTIVE = "hunt_active";
    private static final String DONE = "hunt_done";
    private static final String TRIAL = "hunt_trial";
    private static final String SAFE = "hunt_safe";
    private static final String BAD = "hunt_bad";
    private static final String QUEUE = "hunt_queue";
    private static final String CURRENT = "hunt_current";
    private static final String PENDING = "hunt_pending";
    private static final String ABORT_AFTER = "hunt_abort_after";
    private static final String CRASHES = "hunt_crashes";

    /** Crashes in a row before a hunt gives up, unless the user picks another. */
    public static final int DEFAULT_ABORT_AFTER = 5;

    private Hunt() {
    }

    public static boolean isActive() {
        return PatchDb.getFlag(ACTIVE, false);
    }

    /** True once the queue has run dry and the safe set is waiting to be kept
     *  or thrown away. */
    public static boolean isDone() {
        return PatchDb.getFlag(DONE, false);
    }

    /** True when a trial has been run and nothing has judged it yet. A crash
     *  judges itself; this is what is left for a person to answer. */
    public static boolean awaitingVerdict() {
        return isActive() && !isDone() && PatchDb.getFlag(PENDING, false);
    }

    public static int trial() {
        return PatchDb.getInt(TRIAL, 0);
    }

    public static int safeCount() {
        return ids(SAFE).size();
    }

    public static int badCount() {
        return ids(BAD).size();
    }

    /**
     * What the current trial has switched on: everything cleared so far, plus
     * the block under test.
     *
     * Before the first launch of a trial there is no block under test yet --
     * it is popped when the properties are applied -- so the one at the head of
     * the queue stands in for it. Otherwise a hunt that has just been started
     * would report that it is about to turn nothing on.
     */
    public static int enabledCount() {
        List<Integer> current = ids(CURRENT);
        if (current.isEmpty()) {
            List<List<Integer>> queue = blocks();
            if (!queue.isEmpty()) {
                current = queue.get(0);
            }
        }
        return safeCount() + current.size();
    }

    /** Still to be judged: the block under test plus everything queued behind it. */
    public static int suspectCount() {
        int count = ids(CURRENT).size();
        for (List<Integer> block : blocks()) {
            count += block.size();
        }
        return count;
    }

    public static Set<Integer> bad() {
        return new LinkedHashSet<>(ids(BAD));
    }

    /**
     * Begins a hunt over {@code candidates}, to take effect on the next launch.
     *
     * Nothing is applied here. The first trial goes in when the process next
     * starts, because a flag switched on under a running app reaches only the
     * code that has not already cached it, and a trial has to mean the whole
     * app.
     */
    public static void start(Collection<Integer> candidates, int abortAfter) {
        List<Integer> all = new ArrayList<>(new LinkedHashSet<>(candidates));
        if (all.isEmpty()) {
            Log.e(TAG, "Hunt: nothing to hunt for");
            return;
        }
        PatchDb.setFlag(ACTIVE, true);
        PatchDb.setFlag(DONE, false);
        PatchDb.setFlag(PENDING, false);
        PatchDb.setInt(TRIAL, 0);
        PatchDb.setInt(CRASHES, 0);
        PatchDb.setInt(ABORT_AFTER, Math.max(1, abortAfter));
        write(SAFE, new ArrayList<>());
        write(BAD, new ArrayList<>());
        write(CURRENT, new ArrayList<>());
        // One block holding everything: the first trial is "all of them at
        // once", so a set with nothing wrong in it is finished in one launch.
        writeBlocks(singleBlock(all));
        Log.i(TAG, "Hunt: started over " + all.size() + " propert(ies), giving up after "
                + Math.max(1, abortAfter) + " crash(es) in a row");
    }

    /**
     * Judges the trial that has been running, and works out the next one.
     *
     * Called from the screen for the verdicts a person gives; a crash reaches
     * the same place through {@link #begin}.
     */
    public static void verdict(boolean worked) {
        if (!awaitingVerdict()) {
            return;
        }
        record(worked);
        Log.i(TAG, "Hunt: trial " + trial() + (worked ? " worked" : " was broken")
                + "; " + safeCount() + " cleared, " + suspectCount() + " still suspect");
    }

    /**
     * Applies this launch's trial, having first judged the last one if it
     * crashed.
     *
     * Runs before any hook, from the provider, so that the properties are in
     * place before the app reads its first one.
     */
    public static void begin() {
        if (!isActive() || isDone()) {
            return;
        }
        try {
            if (PatchDb.getFlag(PENDING, false) && BootHealth.lastRunCrashed()) {
                int crashes = PatchDb.getInt(CRASHES, 0) + 1;
                PatchDb.setInt(CRASHES, crashes);
                Log.e(TAG, "Hunt: trial " + trial() + " crashed the app");
                record(false);
                if (crashes >= PatchDb.getInt(ABORT_AFTER, DEFAULT_ABORT_AFTER)) {
                    // Every trial is a different set, so a run of crashes means
                    // something outside the block under test is fatal -- most
                    // likely the safe set itself, through an interaction no
                    // single trial could have shown.
                    Log.e(TAG, "Hunt: " + crashes + " crashes in a row, giving up");
                    finish();
                    return;
                }
            }
            if (!PatchDb.getFlag(PENDING, false)) {
                nextTrial();
            }
            apply();
        } catch (Throwable t) {
            // A hunt is a convenience; it does not get to stop the app starting.
            Log.e(TAG, "Hunt: could not set up this trial, standing down", t);
            stop(false);
        }
    }

    /** PASS promotes the block; FAIL splits it, or blames it when it is one. */
    private static void record(boolean worked) {
        List<Integer> current = ids(CURRENT);
        PatchDb.setFlag(PENDING, false);
        PatchDb.setInt(TRIAL, trial() + 1);
        if (current.isEmpty()) {
            return;
        }
        if (worked) {
            List<Integer> safe = ids(SAFE);
            safe.addAll(current);
            write(SAFE, safe);
            PatchDb.setInt(CRASHES, 0);
        } else if (current.size() == 1) {
            List<Integer> bad = ids(BAD);
            bad.addAll(current);
            write(BAD, bad);
            Log.i(TAG, "Hunt: " + current.get(0) + " is what breaks it");
        } else {
            // Both halves go to the front, so the search finishes with one
            // block before starting the next and the safe set grows in order.
            List<List<Integer>> queue = blocks();
            int middle = current.size() / 2;
            queue.add(0, new ArrayList<>(current.subList(middle, current.size())));
            queue.add(0, new ArrayList<>(current.subList(0, middle)));
            writeBlocks(queue);
        }
        write(CURRENT, new ArrayList<>());
    }

    private static void nextTrial() {
        List<List<Integer>> queue = blocks();
        if (queue.isEmpty()) {
            Log.i(TAG, "Hunt: finished -- " + safeCount() + " propert(ies) can be on together, "
                    + badCount() + " cannot");
            PatchDb.setFlag(DONE, true);
            return;
        }
        List<Integer> block = queue.remove(0);
        writeBlocks(queue);
        write(CURRENT, block);
        PatchDb.setFlag(PENDING, true);
    }

    /** Everything cleared, plus the block being tried. */
    private static void apply() {
        List<Integer> enabled = ids(SAFE);
        enabled.addAll(ids(CURRENT));
        AbPropStore.setHuntEnabled(enabled);
        Log.i(TAG, "Hunt: trial " + trial() + " has " + enabled.size()
                + " propert(ies) on (" + safeCount() + " cleared, "
                + suspectCount() + " suspect)");
    }

    /**
     * Ends the hunt, optionally keeping what it cleared.
     *
     * Keeping writes the safe set into the override table as real overrides,
     * which is the whole product of a hunt: the largest set of the app's own
     * features that can be on at once.
     */
    public static void stop(boolean keepSafe) {
        List<Integer> safe = ids(SAFE);
        if (keepSafe && !safe.isEmpty()) {
            for (Integer id : safe) {
                AbPropStore.set(id, AbProp.Type.BOOL, "true", false);
            }
            Log.i(TAG, "Hunt: kept " + safe.size() + " propert(ies) on");
        }
        finish();
    }

    private static void finish() {
        PatchDb.setFlag(ACTIVE, false);
        PatchDb.setFlag(DONE, false);
        PatchDb.setFlag(PENDING, false);
        AbPropStore.setHuntEnabled(null);
        Log.i(TAG, "Hunt: over");
    }

    private static List<List<Integer>> singleBlock(List<Integer> all) {
        List<List<Integer>> queue = new ArrayList<>();
        queue.add(all);
        return queue;
    }

    // Ids as text, because the settings table holds text and a hunt has to
    // survive the crash it is looking for. Blocks are separated by ";", ids
    // within a block by ",".

    private static List<Integer> ids(String key) {
        return parse(PatchDb.getString(key, ""));
    }

    private static void write(String key, List<Integer> ids) {
        PatchDb.setString(key, join(ids));
    }

    private static List<List<Integer>> blocks() {
        List<List<Integer>> blocks = new ArrayList<>();
        String stored = PatchDb.getString(QUEUE, "");
        for (String block : stored.split(";")) {
            List<Integer> ids = parse(block);
            if (!ids.isEmpty()) {
                blocks.add(ids);
            }
        }
        return blocks;
    }

    private static void writeBlocks(List<List<Integer>> blocks) {
        StringBuilder text = new StringBuilder();
        for (List<Integer> block : blocks) {
            if (text.length() > 0) {
                text.append(';');
            }
            text.append(join(block));
        }
        PatchDb.setString(QUEUE, text.toString());
    }

    private static List<Integer> parse(String text) {
        List<Integer> ids = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return ids;
        }
        for (String part : text.split(",")) {
            try {
                if (!part.isEmpty()) {
                    ids.add(Integer.valueOf(part));
                }
            } catch (NumberFormatException e) {
                Log.e(TAG, "Hunt: " + part + " is not a property id");
            }
        }
        return ids;
    }

    private static String join(List<Integer> ids) {
        StringBuilder text = new StringBuilder();
        for (Integer id : ids) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(id);
        }
        return text.toString();
    }
}
