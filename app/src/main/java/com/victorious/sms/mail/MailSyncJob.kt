package com.victorious.sms.mail

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.atomic.AtomicReference

/**
 * Background mail sync: a periodic JobScheduler job (no extra dependency) that runs [MailRepository.syncAll] when a network is up.
 * It only reads (IMAP INBOX incremental sync); it never sends the outbox, so nothing leaves the phone without the owner opening the app.
 * Logs nothing — no subjects, bodies, addresses or account names.
 */
class MailSyncJob : JobService() {
    private val worker = AtomicReference<Thread?>(null)

    override fun onStartJob(params: JobParameters): Boolean {
        val t = Thread({
            val failures = runCatching { MailRepository.get(applicationContext).syncAll().values.count { it != null } }.getOrDefault(1)
            jobFinished(params, MailSyncPolicy.reschedule(failures))
        }, "mail-sync")
        worker.set(t); t.start(); return true
    }

    override fun onStopJob(params: JobParameters): Boolean { worker.getAndSet(null)?.interrupt(); return true }

    companion object {
        const val JOB_ID = 0x5EC1
        /** Idempotent: re-scheduling with the same id and interval keeps the existing job. */
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, MailSyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(MailSyncPolicy.PERIOD_MS)
                .setBackoffCriteria(MailSyncPolicy.BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build())
        }
        fun cancel(ctx: Context) { ctx.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }
    }
}

/** Pure policy so it is unit-testable. */
object MailSyncPolicy {
    const val PERIOD_MS = 15 * 60 * 1000L        // the JobScheduler minimum
    const val BACKOFF_MS = 60 * 1000L
    /** Ask for a back-off retry only when an account failed; a clean run waits for the next period. */
    fun reschedule(failedAccounts: Int) = failedAccounts > 0
}
