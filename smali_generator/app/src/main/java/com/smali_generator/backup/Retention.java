package com.smali_generator.backup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which runs to drop once there are more than the user asked to keep.
 *
 * Separated from DriveClient because deleting the wrong run is the only
 * irreversible thing this feature does, and arithmetic that talks to no network
 * can be tested exhaustively.
 */
public final class Retention {

    private Retention() {
    }

    /**
     * Run ids are zero-padded UTC timestamps, so lexicographic order is
     * chronological order and Drive's own ordering can be ignored.
     *
     * A keep count of zero or less deletes nothing: a misconfigured or unset
     * setting must never be read as "delete everything".
     */
    public static List<String> toDelete(List<String> runIds, int keep) {
        if (runIds == null || runIds.isEmpty() || keep <= 0 || runIds.size() <= keep) {
            return Collections.emptyList();
        }
        List<String> sorted = new ArrayList<>(runIds);
        Collections.sort(sorted);
        return new ArrayList<>(sorted.subList(0, sorted.size() - keep));
    }
}
