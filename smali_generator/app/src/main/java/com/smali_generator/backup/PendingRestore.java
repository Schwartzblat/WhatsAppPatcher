package com.smali_generator.backup;

import android.content.Context;
import android.util.Log;

import com.smali_generator.BootHealth;
import com.smali_generator.db.PatchDb;

import java.io.File;

/**
 * Carries a verified restore, and its undo, across a process restart.
 *
 * WhatsApp holds msgstore.db open for as long as it is running, so neither can
 * be committed in the session that prepared it. The session stages and verifies,
 * this records the intent, and the move happens at the next start before
 * anything opens the database.
 *
 * Every rule is a pure function of plain values: this runs inside InitProvider,
 * where being wrong means the app does not start, and PatchDb and Context cannot
 * be reached from a unit test in this project.
 */
public final class PendingRestore {

    private static final String TAG = "PATCH";

    private PendingRestore() {
    }

    public static final class Decision {
        public final boolean shouldSwap;
        public final String reason;

        Decision(boolean shouldSwap, String reason) {
            this.shouldSwap = shouldSwap;
            this.reason = reason;
        }
    }

    /** What startup does to the database. At most one, never several. */
    public enum Action {
        NOTHING,
        ROLLBACK,
        UNDO,
        SWAP,
        STAND_ASIDE,
        REFUSE_CONFLICT
    }

    /**
     * The staged path must be the target plus the staging suffix. It is also
     * what makes deleting the staged file safe: a tampered record must not be
     * able to name some other file for that.
     */
    static boolean pairBelongs(String stagedPath, String targetPath) {
        return stagedPath != null && targetPath != null
                && stagedPath.equals(targetPath + RestoreSwap.STAGED_SUFFIX);
    }

    public static Decision decide(String stagedPath, String targetPath,
                                  boolean stagedExists, boolean targetWritable) {
        if (stagedPath == null || stagedPath.isEmpty()
                || targetPath == null || targetPath.isEmpty()) {
            return new Decision(false, "no restore is pending");
        }
        if (!pairBelongs(stagedPath, targetPath)) {
            return new Decision(false, "the recorded pair does not belong together");
        }
        if (!stagedExists) {
            return new Decision(false, "the staged database is gone");
        }
        if (!targetWritable) {
            return new Decision(false, "the database folder cannot be written");
        }
        return new Decision(true, "ready");
    }

    /**
     * Whether a committed restore should be undone because the app stopped
     * starting. The marker's presence is the fact; its value is only for the log.
     */
    public static Decision decideRollback(String committedAt, boolean lastRunEndedBadly,
                                          boolean haveWayBack) {
        if (committedAt == null || committedAt.isEmpty()) {
            return new Decision(false, "no restore is on probation");
        }
        if (!lastRunEndedBadly) {
            return new Decision(false, "the last run ended well");
        }
        if (!haveWayBack) {
            return new Decision(false, "there is no earlier database to go back to");
        }
        return new Decision(true, "the last run ended badly after a restore");
    }

    /**
     * One clean launch is the proof the restored database works, and a marker
     * left behind would roll it back on an unrelated crash weeks later. With no
     * way back there is nothing for the marker to protect either.
     */
    public static boolean probationOver(String committedAt, boolean lastRunEndedBadly,
                                        boolean haveWayBack) {
        if (committedAt == null || committedAt.isEmpty()) {
            return false;
        }
        return !lastRunEndedBadly || !haveWayBack;
    }

    /**
     * Picks the one thing startup may do to the database. Order matters: a
     * rollback outranks everything, and it is the one move safe mode permits,
     * because safe mode is exactly the evidence it waits for. The user's own
     * requests stand aside in safe mode, which is not a time to touch a live
     * database; their records are left alone for when it ends. Two requests at
     * once contradict each other, and a coin toss over somebody's chats is worse
     * than doing neither.
     */
    public static Action plan(boolean safeMode, boolean lastRunEndedBadly, String committedAt,
                              boolean haveWayBack, boolean undoRecorded, boolean swapRecorded) {
        if (decideRollback(committedAt, lastRunEndedBadly, haveWayBack).shouldSwap) {
            return Action.ROLLBACK;
        }
        if (!undoRecorded && !swapRecorded) {
            return Action.NOTHING;
        }
        if (safeMode) {
            return Action.STAND_ASIDE;
        }
        if (undoRecorded && swapRecorded) {
            return Action.REFUSE_CONFLICT;
        }
        return undoRecorded ? Action.UNDO : Action.SWAP;
    }

    public static void record(File staged, File target) {
        PatchDb.setString(RestoreSwap.PENDING_STAGED_KEY, staged.getAbsolutePath());
        PatchDb.setString(RestoreSwap.PENDING_TARGET_KEY, target.getAbsolutePath());
        PatchDb.setString(RestoreSwap.PENDING_AT_KEY, String.valueOf(System.currentTimeMillis()));
    }

    public static void recordUndo(File target) {
        PatchDb.setString(RestoreSwap.PENDING_UNDO_KEY, target.getAbsolutePath());
    }

    public static void clear() {
        PatchDb.setString(RestoreSwap.PENDING_STAGED_KEY, "");
        PatchDb.setString(RestoreSwap.PENDING_TARGET_KEY, "");
        PatchDb.setString(RestoreSwap.PENDING_AT_KEY, "");
    }

    private static void clearUndo() {
        PatchDb.setString(RestoreSwap.PENDING_UNDO_KEY, "");
    }

    private static void clearCommitted() {
        PatchDb.setString(RestoreSwap.COMMITTED_AT_KEY, "");
        PatchDb.setString(RestoreSwap.COMMITTED_TARGET_KEY, "");
    }

    private static String get(String key) {
        String v = PatchDb.getString(key, "");
        return v == null ? "" : v;
    }

    /**
     * Called from InitProvider.on_load(). Never throws.
     *
     * Every launch that actually attempts a move clears its record first, so a
     * move that kills the process is not retried for ever. Safe mode never
     * attempts one and so leaves its records alone, which does not contradict
     * that: the clear-before-move rule is about attempts, and standing aside is
     * not one.
     */
    public static void applyPending(Context context, BootHealth.Mode mode) {
        try {
            doApply(mode);
        } catch (Throwable t) {
            Log.e(TAG, "PendingRestore: failed", t);
        }
    }

    private static void doApply(BootHealth.Mode mode) {
        if (mode == null) {
            return;
        }
        boolean badly = mode != BootHealth.Mode.NORMAL;
        String committedAt = get(RestoreSwap.COMMITTED_AT_KEY);
        String committedTarget = get(RestoreSwap.COMMITTED_TARGET_KEY);
        String undoTarget = get(RestoreSwap.PENDING_UNDO_KEY);
        String stagedPath = get(RestoreSwap.PENDING_STAGED_KEY);
        String targetPath = get(RestoreSwap.PENDING_TARGET_KEY);

        boolean haveWayBack = !committedTarget.isEmpty()
                && RestoreSwap.hasUndo(new File(committedTarget));
        if (probationOver(committedAt, badly, haveWayBack)) {
            clearCommitted();
            Log.i(TAG, "PendingRestore: restore is out of probation");
        }

        Action action = plan(mode == BootHealth.Mode.SAFE, badly, committedAt, haveWayBack,
                !undoTarget.isEmpty(), !stagedPath.isEmpty() || !targetPath.isEmpty());
        switch (action) {
            case ROLLBACK: {
                // Cleared whatever the result: a rollback that fails must not be
                // retried on every launch, and one that works has nothing to retry.
                clearCommitted();
                clearUndo();
                RestoreSwap.Result r = RestoreSwap.undo(new File(committedTarget));
                Log.e(TAG, "PendingRestore: the app did not start after a restore, so the"
                        + " earlier database was put back. ok=" + r.ok + " -- " + r.message);
                return;
            }
            case STAND_ASIDE:
                Log.w(TAG, "PendingRestore: safe mode, leaving the pending request alone");
                return;
            case REFUSE_CONFLICT:
                clear();
                clearUndo();
                if (pairBelongs(stagedPath, targetPath)) {
                    new File(stagedPath).delete();
                }
                Log.e(TAG, "PendingRestore: both a restore and an undo were requested;"
                        + " doing neither");
                return;
            case UNDO: {
                clearUndo();
                // The restored database is leaving, so it is no longer on probation.
                clearCommitted();
                RestoreSwap.Result r = RestoreSwap.undo(new File(undoTarget));
                Log.i(TAG, "PendingRestore: undo ok=" + r.ok + " -- " + r.message);
                return;
            }
            case SWAP:
                applySwap(stagedPath, targetPath);
                return;
            default:
        }
    }

    private static void applySwap(String stagedPath, String targetPath) {
        File staged = new File(stagedPath);
        File target = new File(targetPath);
        File parent = target.getParentFile();
        Decision decision = decide(stagedPath, targetPath, staged.isFile(),
                parent != null && parent.canWrite());
        if (!decision.shouldSwap) {
            Log.w(TAG, "PendingRestore: not swapping -- " + decision.reason);
            if (pairBelongs(stagedPath, targetPath)) {
                staged.delete();
            }
            clear();
            return;
        }
        // Cleared before the move, not after: a swap that kills the process must
        // not be retried on the next launch.
        clear();
        RestoreSwap.Result result = RestoreSwap.swap(staged, target);
        Log.i(TAG, "PendingRestore: swap ok=" + result.ok + " -- " + result.message);
        if (result.ok) {
            PatchDb.setString(RestoreSwap.COMMITTED_TARGET_KEY, target.getAbsolutePath());
            PatchDb.setString(RestoreSwap.COMMITTED_AT_KEY, String.valueOf(System.currentTimeMillis()));
        }
    }
}
