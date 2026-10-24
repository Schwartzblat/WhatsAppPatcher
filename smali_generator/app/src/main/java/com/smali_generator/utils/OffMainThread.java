package com.smali_generator.utils;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Runs blocking work on a new daemon thread and waits a bounded time. Pure Java. */
public final class OffMainThread {
    private OffMainThread() {}

    /** The work's result, or onTimeout on a timeout, an exception or an interrupt. */
    public static <T> T call(Callable<T> work, long timeoutMillis, T onTimeout) {
        FutureTask<T> task = new FutureTask<>(work);
        Thread t = new Thread(task, "OffMainThread");
        t.setDaemon(true);
        t.start();
        try {
            return task.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            return onTimeout;
        } catch (Exception e) {
            task.cancel(true);
            return onTimeout;
        }
    }
}
