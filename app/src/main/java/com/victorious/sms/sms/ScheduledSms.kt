package com.victorious.sms.sms

import android.app.AlarmManager
import android.app.PendingIntent
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.victorious.comms.Author
import com.victorious.comms.ScheduledMessage
import com.victorious.comms.Scheduler
import com.victorious.sms.archive.ArchiveRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Victorious Framework: owner-scheduled texts on Android. The rules live in `comms-core` ([Scheduler]); this is only the
 * alarm and the sender around it.
 *
 *  - The alarm is *inexact* on purpose (`setAndAllowWhileIdle`): it needs no exact-alarm permission, and Android may delay it.
 *    A message more than 15 minutes late is marked MISSED by the scheduler and shown to the owner; it is never sent late.
 *  - Alarms do not survive a reboot or an app update, so [ScheduledSmsReceiver] re-arms on boot and the app re-arms on start.
 *  - Only text the owner typed is sent, as [Author.OWNER]; nothing here is automation.
 *  - Never run on a device. The send itself needs SEND_SMS and a real telephony stack.
 */
object ScheduledSms {
    internal const val ACTION_DUE = "com.victorious.sms.SCHEDULED_SMS_DUE"
    private const val NO_NUMBER = "This conversation has no single phone number to text."
    private const val NOT_ACCEPTED = "Android did not accept the text. Check the thread before sending it again."

    /** Swapped in tests. Returns false when Android refused or the permission is missing. */
    @Volatile internal var sender: (Context, String, String) -> Boolean = { c, to, body -> SmsRepository(c).send(to, body, Author.OWNER) }

    @Volatile private var recovered = false
    private val changes = MutableStateFlow(0L)
    val revisions = changes.asStateFlow()

    fun recipient(context: Context, conversation: Long): String? =
        ArchiveRuntime.get(context).archive.smsRecipient(conversation)

    fun canSend(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    fun schedule(context: Context, conversation: Long, body: String, sendAt: Long): ScheduledMessage {
        require(canSend(context)) { "Allow SMS sending in Android settings first." }
        require(recipient(context, conversation) != null) { NO_NUMBER }
        val m = scheduler(context).schedule(conversation, body, sendAt)
        arm(context)
        changes.update { it + 1 }
        return m
    }

    fun cancel(context: Context, uid: String): Boolean =
        scheduler(context).cancel(uid).also {
            arm(context)
            changes.update { it + 1 }
        }

    fun list(context: Context, conversation: Long?): List<ScheduledMessage> =
        scheduler(context).list(conversation, limit = Scheduler.MAX_PENDING.toInt() + 100)

    /**
     * Sends whatever is due. Returns how many texts Android accepted. Safe to call from any thread; calls are serialised.
     * A send in flight when the previous process died is turned into UNKNOWN once per process, before anything is claimed.
     */
    @Synchronized
    fun runDue(context: Context, scheduler: Scheduler = scheduler(context), archiveRecipient: (Long) -> String? = { recipient(context, it) }): Int {
        recover(scheduler)
        var sent = 0
        for (m in scheduler.claimDue()) {
            val to = runCatching { archiveRecipient(m.conversation) }.getOrNull()
            when {
                to == null -> scheduler.markFailed(m.uid, NO_NUMBER)
                runCatching { sender(context, to, m.body) }.getOrDefault(false) -> { scheduler.markSent(m.uid); sent++ }
                else -> scheduler.markFailed(m.uid, NOT_ACCEPTED)
            }
        }
        changes.update { it + 1 }
        return sent
    }

    /** Sets (or clears) the one alarm for the earliest pending message. Never throws. */
    @Synchronized
    fun arm(context: Context, scheduler: Scheduler? = null) {
        runCatching {
            val app = context.applicationContext
            // Nothing can be scheduled before the archive exists; do not create it just to find that out.
            if (scheduler == null && !app.getDatabasePath(ArchiveRuntime.DB_NAME).exists()) return
            val sched = scheduler ?: scheduler(app)
            recover(sched)
            val alarms = app.getSystemService(AlarmManager::class.java) ?: return
            val pi = alarmIntent(app)
            val next = sched.nextDue()
            if (next == null) alarms.cancel(pi)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(next, System.currentTimeMillis() + 1_000), pi)
        }
    }

    internal fun resetForTests() { recovered = false }

    private fun recover(scheduler: Scheduler) {
        if (recovered) return
        val count = scheduler.recoverAfterRestart()
        recovered = true
        if (count > 0) changes.update { it + 1 }
    }

    private fun alarmIntent(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app, 0, Intent(app, ScheduledSmsReceiver::class.java).setAction(ACTION_DUE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun scheduler(context: Context) = Scheduler(ArchiveRuntime.get(context).archive)

}

/** Fires when a scheduled text is due, and after a reboot or app update (alarms are lost then). Not exported. */
class ScheduledSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ScheduledSms.ACTION_DUE && action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        Thread {
            try {
                if (action == ScheduledSms.ACTION_DUE) ScheduledSms.runDue(context)
                ScheduledSms.arm(context)
            } catch (_: Throwable) {
                // A failure here must not crash the process; the next alarm or app start tries again.
            } finally {
                pending.finish()
            }
        }.start()
    }
}
