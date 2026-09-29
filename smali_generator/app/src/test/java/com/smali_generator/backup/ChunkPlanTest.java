package com.smali_generator.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Content-Range is one-based-inclusive at the end and Drive's resume reply is
 * the last byte it holds, not the next one it wants. Both are off-by-one traps
 * that corrupt an upload without failing it.
 */
public class ChunkPlanTest {

    @Test
    public void a_full_chunk_is_ranged_inclusively() {
        assertEquals("bytes 0-8388607/13000000",
                ChunkPlan.contentRange(0, ChunkPlan.CHUNK_BYTES, 13_000_000L));
    }

    @Test
    public void the_last_short_chunk_ends_at_the_last_byte() {
        long start = ChunkPlan.CHUNK_BYTES;
        long length = 13_000_000L - start;
        assertEquals("bytes 8388608-12999999/13000000",
                ChunkPlan.contentRange(start, length, 13_000_000L));
    }

    @Test
    public void a_one_byte_file_is_a_valid_range() {
        assertEquals("bytes 0-0/1", ChunkPlan.contentRange(0, 1, 1));
    }

    @Test
    public void resume_starts_one_past_the_last_byte_the_server_holds() {
        // "bytes=0-1048575" means it has 1048576 bytes, so the next one is at
        // 1048576. Sending from 1048575 would duplicate a byte.
        assertEquals(1_048_576L, ChunkPlan.nextStart("bytes=0-1048575"));
    }

    @Test
    public void an_absent_range_header_means_start_from_the_beginning() {
        assertEquals(0L, ChunkPlan.nextStart(null));
        assertEquals(0L, ChunkPlan.nextStart(""));
    }

    @Test
    public void a_malformed_range_header_means_start_from_the_beginning() {
        // Restarting wastes bandwidth; guessing an offset corrupts the file.
        assertEquals(0L, ChunkPlan.nextStart("bytes=nonsense"));
        assertEquals(0L, ChunkPlan.nextStart("0-100"));
    }

    @Test
    public void chunk_length_is_capped_by_the_chunk_size_and_by_the_file() {
        assertEquals(ChunkPlan.CHUNK_BYTES, ChunkPlan.chunkLength(0, 100_000_000L));
        assertEquals(100L, ChunkPlan.chunkLength(13_000_000L - 100, 13_000_000L));
        assertEquals(0L, ChunkPlan.chunkLength(13_000_000L, 13_000_000L));
    }

    @Test
    public void a_dead_session_is_recognised_so_the_upload_restarts() {
        // Drive expires session URIs after about a week. Retrying a dead one
        // forever is the failure mode this guards.
        assertTrue(ChunkPlan.sessionIsDead(404));
        assertTrue(ChunkPlan.sessionIsDead(410));
        assertFalse(ChunkPlan.sessionIsDead(308));
        assertFalse(ChunkPlan.sessionIsDead(200));
        assertFalse(ChunkPlan.sessionIsDead(503));
    }
}
