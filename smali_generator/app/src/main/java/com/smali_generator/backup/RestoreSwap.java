package com.smali_generator.backup;

import java.io.File;

/**
 * Commits a staged database over the live one, and undoes it.
 *
 * It takes files rather than a Context so that every path through it is
 * testable on a bare JVM: this is the only code in the patch that moves
 * somebody's msgstore.db, and it runs from InitProvider while the app starts.
 *
 * The displaced database is kept as msgstore.db.pre-restore and is never
 * overwritten by a later swap, so the way back always reaches the database the
 * user actually started with rather than the result of their previous attempt.
 * The corollary is that a LATER swap keeps nothing of the database it replaces:
 * every sentence shown to the user has to say which of the two happened.
 */
public final class RestoreSwap {

    public static final String ASIDE_SUFFIX = ".pre-restore";
    public static final String STAGED_SUFFIX = ".restoring";
    private static final String SCRATCH_SUFFIX = ".replaced";

    public static final String PENDING_STAGED_KEY = "restore_pending_staged";
    public static final String PENDING_TARGET_KEY = "restore_pending_target";
    public static final String PENDING_AT_KEY = "restore_pending_at";
    /**
     * The target path of an undo the user asked for. Its own key rather than a
     * flag on the swap's record: a pending swap and a pending undo mean opposite
     * things, and keeping them apart is what lets startup notice both at once.
     */
    public static final String PENDING_UNDO_KEY = "restore_pending_undo";
    /**
     * A restore is on probation from the moment it is committed until a launch
     * ends well. Its own pair of keys, never the pending ones: those are cleared
     * on purpose before the move, and this has to outlive the move.
     */
    /**
     * What startup last did to the database, in words for the screen. Startup
     * has no UI, so without this the sentence naming where somebody's chats
     * went would exist only in logcat.
     */
    public static final String RESULT_KEY = "restore_last_result";
    public static final String COMMITTED_AT_KEY = "restore_committed_at";
    public static final String COMMITTED_TARGET_KEY = "restore_committed_target";

    /**
     * SQLite replays these into whatever database sits beside them, so a log
     * belonging to the old file would be replayed into the restored one.
     */
    private static final String[] SIDECARS = {"-wal", "-shm", "-journal"};

    private RestoreSwap() {
    }

    public static final class Result {
        public final boolean ok;
        public final String message;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    public static File asideFile(File target) {
        return new File(target.getParentFile(), target.getName() + ASIDE_SUFFIX);
    }

    public static File stagedFile(File target) {
        return new File(target.getParentFile(), target.getName() + STAGED_SUFFIX);
    }

    public static boolean hasUndo(File target) {
        File aside = asideFile(target);
        return aside.isFile() && aside.length() > 0;
    }

    private static File sibling(File target, String suffix) {
        return new File(target.getParentFile(), target.getName() + suffix);
    }

    private static boolean sameFile(File a, File b) {
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (java.io.IOException | RuntimeException e) {
            // Cannot prove they differ, and aliasing is how a swap eats its input.
            return true;
        }
    }

    public static Result swap(File staged, File target) {
        try {
            return doSwap(staged, target);
        } catch (RuntimeException e) {
            return new Result(false, "Unexpected failure: " + e);
        }
    }

    private static Result doSwap(File staged, File target) {
        if (staged == null || target == null) {
            return new Result(false, "Nothing to put in place.");
        }
        if (!staged.isFile() || staged.length() == 0) {
            return new Result(false, "There is no staged database to put in place.");
        }
        File aside = asideFile(target);
        File scratch = sibling(target, SCRATCH_SUFFIX);
        if (sameFile(staged, target) || sameFile(staged, aside) || sameFile(target, aside)) {
            return new Result(false, "The staged, live and kept databases must be different files.");
        }
        if (scratch.exists()) {
            if (!target.exists() && scratch.isFile()) {
                // A crash between the two renames below leaves the live database
                // only here. Deleting it as "stale" would destroy the user's data.
                if (!scratch.renameTo(target)) {
                    return new Result(false, "An earlier restore was interrupted and its database"
                            + " could not be put back. It is at " + scratch.getAbsolutePath());
                }
                // The recovered database keeps its own log; leaving it behind would
                // either orphan it or hand it to the delete below.
                moveSidecars(scratch, target);
            } else if (scratch.isFile() && !hasUndo(target)) {
                // With a target present and no way back recorded, the scratch is the
                // database that was live before a swap that died after landing its
                // staged file and before recording this one: the user's original,
                // which is exactly what the aside is for. Deleting it would make the
                // first restore's result the way back. It travels with its own log.
                if (!scratch.renameTo(aside)) {
                    return new Result(false, "An earlier restore was interrupted and the database"
                            + " it displaced could not be kept. It is at " + scratch.getAbsolutePath()
                            + ". Nothing was changed.");
                }
                moveSidecars(scratch, aside);
            } else {
                // A way back already exists, and the true original outranks an
                // intermediate left by an interrupted later swap.
                scratch.delete();
            }
        }
        // Only the FIRST swap writes the way back; a later one would replace the
        // user's original with the result of their previous restore. Decided with
        // hasUndo, the same test the UI uses, so a zero-byte or non-file aside
        // cannot send this down a branch that reports no way back. Read AFTER the
        // rescue above, which may just have created the aside: the answer from
        // before it would let this swap rename the intermediate over the original.
        boolean keepAsWayBack = !hasUndo(target);
        boolean hadLiveDatabase = target.isFile();
        // scratch is resolved by now (recovered to target, or deleted), so a log
        // still at scratch-* is from an interrupted run and is not the live
        // database's. Left there, the next move into scratch would pair it with a
        // database it does not belong to. This does not contradict never deleting a
        // stale scratch: that is about the database file, this is only its log.
        deleteSidecars(scratch);

        // The live database is moved aside, never deleted: until the staged file is
        // safely in place this rename is the only thing standing between a failure
        // and somebody's chat history.
        if (target.isFile() && !target.renameTo(scratch)) {
            return new Result(false, "Could not move the current database aside."
                    + " Nothing was changed.");
        }
        // The log belongs to the displaced database, so it leaves before the new
        // file arrives: a crash after the next rename must not find the new
        // database beside the old -wal, which SQLite would replay into it.
        if (scratch.isFile() && moveSidecars(target, scratch) != null) {
            moveSidecars(scratch, target);
            if (scratch.renameTo(target)) {
                return new Result(false, "Could not move the current database's log aside."
                        + " Nothing was changed.");
            }
            return new Result(false, "Could not move the current database's log aside, and could"
                    + " not put the database back. It is at " + scratch.getAbsolutePath());
        }
        if (!staged.renameTo(target)) {
            // The log goes back with the database, or the old one returns
            // without its recent transactions.
            moveSidecars(scratch, target);
            if (scratch.isFile() && scratch.renameTo(target)) {
                return new Result(false, "Could not put the restored database in place."
                        + " The database you had is back where it was.");
            }
            if (!scratch.isFile()) {
                return new Result(false, "Could not put the restored database in place."
                        + " There was no database to put back.");
            }
            return new Result(false, "Could not put the restored database in place, and could"
                    + " not put the previous one back either. It is at " + scratch.getAbsolutePath());
        }
        boolean kept = false;
        if (keepAsWayBack && scratch.isFile()) {
            if (!scratch.renameTo(aside)) {
                // The restore succeeded; only the way back could not be recorded.
                return new Result(false, "Restored, but the previous database could not be kept"
                        + " as a way back. It is at " + scratch.getAbsolutePath());
            }
            // Committed transactions live in the -wal until checkpointed, so the
            // way back is incomplete without the log that belongs to it.
            moveSidecars(scratch, aside);
            kept = true;
        } else {
            scratch.delete();
            deleteSidecars(scratch);
        }
        // Belt and braces: the log left before the new file arrived, so this
        // should find nothing.
        String stale = deleteSidecars(target);
        if (stale != null) {
            return new Result(false, "Restored, but " + stale + " could not be removed."
                    + " Close and reopen WhatsApp before using it.");
        }
        return new Result(true, successMessage(kept, hadLiveDatabase, aside.getName()));
    }

    /**
     * Worded from what is on disk, not from what the dialog promised: the three
     * outcomes leave different things behind and a sentence that fits only the
     * first sends somebody to look for a copy that is not there.
     */
    static String successMessage(boolean keptAsWayBack, boolean hadLiveDatabase, String asideName) {
        if (keptAsWayBack) {
            return "Restored. The database you had is kept as " + asideName
                    + " in case you want it back.";
        }
        if (!hadLiveDatabase) {
            return "Restored. There was no database on this phone to keep, so there is"
                    + " nothing to undo back to.";
        }
        return "Restored. The copy kept for Undo is still the one from before your first"
                + " restore (" + asideName + "); the database this replaced was not kept.";
    }

    /**
     * Puts a database stranded by a swap that died between its two renames back
     * where WhatsApp looks for it. Idempotent and total: it acts only when there
     * is no database at all and one sits in the scratch slot, a state no
     * completed run leaves. The pending record was cleared before that swap, on
     * purpose, so nothing else would ever look; without this WhatsApp creates a
     * fresh empty database and the user's chats appear gone.
     * Returns true only when it moved something.
     */
    public static boolean rescueStranded(File target) {
        try {
            if (target == null) {
                return false;
            }
            File scratch = sibling(target, SCRATCH_SUFFIX);
            if (target.exists() || !scratch.isFile()) {
                return false;
            }
            if (!scratch.renameTo(target)) {
                return false;
            }
            moveSidecars(scratch, target);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static Result undo(File target) {
        try {
            return doUndo(target);
        } catch (RuntimeException e) {
            return new Result(false, "Unexpected failure: " + e);
        }
    }

    private static Result doUndo(File target) {
        if (target == null) {
            return new Result(false, "Nothing to undo.");
        }
        File aside = asideFile(target);
        if (!hasUndo(target)) {
            return new Result(false, "There is no earlier database kept to go back to.");
        }
        File scratch = sibling(target, SCRATCH_SUFFIX);
        if (scratch.exists()) {
            if (!target.exists() && scratch.isFile()) {
                // Same rescue as swap: a swap killed between its renames leaves the
                // live database only here, and undo must not be what deletes it.
                // No test reaches this. On the success path the end state is the same
                // either way, and the path where it matters needs aside.renameTo to
                // fail with the target free -- impossible here, since asideFile puts
                // the aside in the target's own directory. Keep it: swap's twin is
                // tested, and the alternative is deleting somebody's only database.
                if (!scratch.renameTo(target)) {
                    return new Result(false, "An earlier restore was interrupted and its database"
                            + " could not be put back. It is at " + scratch.getAbsolutePath());
                }
                moveSidecars(scratch, target);
            } else {
                scratch.delete();
            }
        }
        // scratch is resolved by now (recovered to target, or deleted), so a log
        // still at scratch-* is from an interrupted run and is not the live
        // database's. Left there, the next move into scratch would pair it with a
        // database it does not belong to. This does not contradict never deleting a
        // stale scratch: that is about the database file, this is only its log.
        deleteSidecars(scratch);
        // Same rule as swap: the restored database is moved aside, not deleted,
        // until the earlier one is in place.
        if (target.isFile() && !target.renameTo(scratch)) {
            return new Result(false, "Could not move the restored database aside."
                    + " Nothing was changed.");
        }
        if (scratch.isFile() && moveSidecars(target, scratch) != null) {
            moveSidecars(scratch, target);
            if (scratch.renameTo(target)) {
                return new Result(false, "Could not move the restored database's log aside."
                        + " Nothing was changed.");
            }
            return new Result(false, "Could not move the restored database's log aside, and could"
                    + " not put the database back. It is at " + scratch.getAbsolutePath());
        }
        if (!aside.renameTo(target)) {
            // Unreachable from a test, for the reason given at the rescue above.
            moveSidecars(scratch, target);
            if (scratch.isFile() && scratch.renameTo(target)) {
                return new Result(false, "Could not put the earlier database back."
                        + " The current one is back where it was.");
            }
            return new Result(false, "Could not put the earlier database back."
                    + " The current one is at " + scratch.getAbsolutePath());
        }
        scratch.delete();
        // The restored database's log already left with it, so nothing stale
        // sits beside the earlier database; its own log comes back now.
        deleteSidecars(scratch);
        // Property held here: undo never returns with the earlier database at the
        // target and its log left at aside-*. So the stale check runs first (it
        // would delete the log if it ran after), the log is moved in regardless of
        // what it found, and anything still wrong is named rather than dropped.
        String stale = deleteSidecars(target);
        String stranded = moveSidecars(aside, target);
        if (stale != null || stranded != null) {
            String msg = "Went back, but";
            if (stale != null) {
                msg += " " + stale + " could not be removed.";
            }
            if (stranded != null) {
                msg += " The earlier database's log is still at " + stranded + ".";
            }
            return new Result(false, msg + " Close and reopen WhatsApp before using it.");
        }
        return new Result(true, "Put the earlier database back.");
    }

    /** Returns the name of the first sidecar that could not be moved, or null. */
    private static String moveSidecars(File from, File to) {
        for (String suffix : SIDECARS) {
            File src = sibling(from, suffix);
            if (src.exists() && !src.renameTo(sibling(to, suffix))) {
                return src.getName();
            }
        }
        return null;
    }

    /** Returns the name of the first sidecar that survives, or null. */
    private static String deleteSidecars(File target) {
        for (String suffix : SIDECARS) {
            File f = sibling(target, suffix);
            if (f.exists() && !f.delete() && f.exists()) {
                return f.getName();
            }
        }
        return null;
    }
}
