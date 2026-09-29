package com.smali_generator.backup;

/**
 * The arithmetic of a Drive resumable upload, with no network in it.
 *
 * Resumable rather than multipart because a 13 MB database is over Drive's 5 MB
 * multipart ceiling. Both conversions here are off-by-one traps: Content-Range
 * names the last byte in the chunk, and Drive's resume reply names the last byte
 * it already holds rather than the next one it wants.
 */
public final class ChunkPlan {

    /** Large enough that a 13 MB database is two round trips, small enough to retry. */
    public static final int CHUNK_BYTES = 8 * 1024 * 1024;

    private ChunkPlan() {
    }

    /** "bytes <first>-<last>/<total>", where last is inclusive. */
    public static String contentRange(long start, long length, long total) {
        return "bytes " + start + "-" + (start + length - 1) + "/" + total;
    }

    /**
     * Drive answers an interrupted upload with "Range: bytes=0-<last stored>".
     * The next byte to send is one past that. A missing or malformed header
     * restarts from zero: that costs bandwidth, where a guessed offset would
     * cost a corrupt file that still uploads successfully.
     */
    public static long nextStart(String rangeHeader) {
        if (rangeHeader == null) {
            return 0L;
        }
        int dash = rangeHeader.lastIndexOf('-');
        if (!rangeHeader.startsWith("bytes=") || dash < 0) {
            return 0L;
        }
        try {
            return Long.parseLong(rangeHeader.substring(dash + 1).trim()) + 1L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public static long chunkLength(long start, long total) {
        return Math.max(0L, Math.min((long) CHUNK_BYTES, total - start));
    }

    /**
     * A session URI Drive has forgotten. It expires them after about a week, and
     * retrying a dead one never succeeds -- the upload has to start again.
     */
    public static boolean sessionIsDead(int httpStatus) {
        return httpStatus == 404 || httpStatus == 410;
    }
}
