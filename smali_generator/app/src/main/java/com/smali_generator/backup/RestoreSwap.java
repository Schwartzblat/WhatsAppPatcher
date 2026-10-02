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

    public static Result swap(File staged, File target) {
        if (staged == null || !staged.isFile() || staged.length() == 0) {
            return new Result(false, "There is no staged database to put in place.");
        }
        File aside = asideFile(target);
        if (target.isFile()) {
            // Only the first swap writes the way back. A second one would
            // otherwise replace the user's original database with the result of
            // their previous restore, which is not a way back at all.
            if (!aside.exists()) {
                if (!target.renameTo(aside)) {
                    return new Result(false, "Could not move the current database aside."
                            + " Nothing was changed.");
                }
            } else if (!target.delete()) {
                return new Result(false, "Could not clear the current database."
                        + " Nothing was changed.");
            }
        }
        if (!staged.renameTo(target)) {
            // Put the original back rather than leave the app with no database.
            if (!target.exists() && aside.isFile()) {
                aside.renameTo(target);
            }
            return new Result(false, "Could not put the restored database in place."
                    + " The original is back where it was.");
        }
        deleteSidecars(target);
        return new Result(true, "Restored. The database you had is kept as "
                + aside.getName() + " in case you want it back.");
    }

    public static Result undo(File target) {
        File aside = asideFile(target);
        if (!aside.isFile() || aside.length() == 0) {
            return new Result(false, "There is no earlier database kept to go back to.");
        }
        if (target.isFile() && !target.delete()) {
            return new Result(false, "Could not clear the restored database. Nothing was changed.");
        }
        if (!aside.renameTo(target)) {
            return new Result(false, "Could not put the earlier database back.");
        }
        // The restored database's own log must go too, for the same reason.
        deleteSidecars(target);
        return new Result(true, "Put the earlier database back.");
    }

    private static void deleteSidecars(File target) {
        for (String suffix : SIDECARS) {
            new File(target.getParentFile(), target.getName() + suffix).delete();
        }
    }
}
