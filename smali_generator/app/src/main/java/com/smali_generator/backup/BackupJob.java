package com.smali_generator.backup;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * Runs the upload on JobScheduler's schedule.
 *
 * A probe job carrying only a latency and a deadline sat READY without being
 * dispatched once the app went to background, which is why this one asks for
 * real constraints instead: an unmetered network is a thing the scheduler
 * actively waits for, and a periodic job is a thing it actively runs.
 */
public class BackupJob extends JobService {

    private static final String TAG = "PATCH";
    public static final int JOB_ID = 0x7BAC;
    private static final long PERIOD_MS = 24 * 60 * 60 * 1000L;

    public static void schedule(Context context, boolean requiresCharging) {
        try {
            JobScheduler scheduler =
                    (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            // schedule() replaces by id, so calling this on every load() is
            // idempotent and picks up a changed constraint without a duplicate.
            int rc = scheduler.schedule(
                    new JobInfo.Builder(JOB_ID, new ComponentName(context, BackupJob.class))
                            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                            .setRequiresBatteryNotLow(true)
                            .setRequiresCharging(requiresCharging)
                            .setPersisted(true)
                            .setPeriodic(PERIOD_MS)
                            .build());
            Log.i(TAG, "BackupJob: scheduled, rc=" + rc + " charging=" + requiresCharging);
        } catch (Throwable t) {
            Log.e(TAG, "BackupJob: cannot schedule", t);
        }
    }

    public static void cancel(Context context) {
        try {
            ((JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).cancel(JOB_ID);
            Log.i(TAG, "BackupJob: cancelled");
        } catch (Throwable t) {
            Log.e(TAG, "BackupJob: cannot cancel", t);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            String outcome;
            try {
                outcome = BackupRunner.runOnce(getApplicationContext());
            } catch (Throwable t) {
                // A job that throws is a job the scheduler stops trusting.
                Log.e(TAG, "BackupJob: run failed", t);
                outcome = "Failed: " + t.getClass().getSimpleName();
            }
            Log.i(TAG, "BackupJob: finished -- " + outcome);
            jobFinished(params, false);
        }, "drive-backup").start();
        // True: the work continues on that thread after this returns.
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Resumable uploads mean a killed run resumes rather than restarts.
        return true;
    }
}
