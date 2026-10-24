package com.smali_generator.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OffMainThreadTest {
    @Test
    public void returnsTheResult() {
        final Thread[] ran = new Thread[1];
        String r = OffMainThread.call(() -> { ran[0] = Thread.currentThread(); return "x"; }, 1000, "t");
        assertEquals("x", r);
        assertNotEquals(Thread.currentThread(), ran[0]);
    }

    @Test
    public void timesOut() {
        long start = System.currentTimeMillis();
        String r = OffMainThread.call(() -> { Thread.sleep(2000); return "x"; }, 100, "t");
        assertEquals("t", r);
        assertTrue(System.currentTimeMillis() - start < 1000);
    }

    @Test
    public void exceptionGivesTheFallback() {
        String r = OffMainThread.call(() -> { throw new IllegalStateException("no"); }, 1000, "t");
        assertEquals("t", r);
    }
}
