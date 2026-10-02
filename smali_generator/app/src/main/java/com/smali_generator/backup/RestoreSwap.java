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
 */
public final class RestoreSwap {

    public static final String ASIDE_SUFFIX = ".pre-restore";
    public static final String STAGED_SUFFIX = ".restoring";
    private static final String SCRATCH_SUFFIX = ".replaced";

    public static final String PENDING_STAGED_KEY = "restore_pending_staged";
    public static final String PENDING_TARGET_KEY = "restore_pending_target";
    public static final String PENDING_AT_KEY = "restore_pending_at";

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
        // Only the FIRST swap writes the way back; a later one would replace the
        // user's original with the result of their previous restore. Decided with
        // hasUndo, the same test the UI uses, so a zero-byte or non-file aside
        // cannot send this down a branch that reports no way back.
        boolean keepAsWayBack = !hasUndo(target);

        if (scratch.exists()) {
            if (!target.exists() && scratch.isFile()) {
                // A crash between the two renames below leaves the live database
                // only here. Deleting it as "stale" would destroy the user's data.
                if (!scratch.renameTo(target)) {
                    return new Result(false, "An earlier restore was interrupted and its database"
                            + " could not be put back. It is at " + scratch.getAbsolutePath());
                }
            } else {
                scratch.delete();
            }
        }

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
        if (keepAsWayBack && scratch.isFile()) {
            if (!scratch.renameTo(aside)) {
                // The restore succeeded; only the way back could not be recorded.
                return new Result(false, "Restored, but the previous database could not be kept"
                        + " as a way back. It is at " + scratch.getAbsolutePath());
            }
            // Committed transactions live in the -wal until checkpointed, so the
            // way back is incomplete without the log that belongs to it.
            moveSidecars(scratch, aside);
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
        return new Result(true, "Restored. The database you had is kept as "
                + aside.getName() + " in case you want it back.");
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
        scratch.delete();
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
        // Checked before the earlier log is moved in, or it would be deleted as stale.
        String stale = deleteSidecars(target);
        if (stale != null) {
            return new Result(false, "Went back, but " + stale + " could not be removed."
                    + " Close and reopen WhatsApp before using it.");
        }
        moveSidecars(aside, target);
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
