package com.maxapacity.ride;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Sends rides home when the phone reaches Wi-Fi, even if the app is not
 * running: the system starts this once an unmetered network is up. If the
 * Deck is not on that network it tries again later, further apart each time.
 */
public final class DeckJob extends JobService {

    private static final int ID = 7;

    /** Asks for a run the next time the phone is on Wi-Fi. Asking twice is the same as once. */
    static void schedule(Context c) {
        try {
            JobScheduler jobs = c.getSystemService(JobScheduler.class);
            if (jobs == null) return;
            jobs.schedule(new JobInfo.Builder(ID, new ComponentName(c, DeckJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setBackoffCriteria(15 * 60 * 1000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .setPersisted(true)
                    .build());
        } catch (RuntimeException e) {
            // Not allowed on this phone: rides still go when the app is opened at home.
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        Deck.get(this).offload((ok, message) -> jobFinished(params, !ok));
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Cut short (the network went): whatever did not arrive is still marked unsent.
        return true;
    }
}
