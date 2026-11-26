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
    private const val UNCERTAIN = "Submission was interrupted. Check SMS activity before sending again."

    /** Uses the schedule UID as the durable carrier-send request key. */
    @Volatile internal var sender: (Context, String, String, Int?, String) -> SmsSubmission = { c, to, body, sim, key ->
        SmsRepository(c).send(to, body, Author.OWNER, requestedSim = sim, requestKey = key)
    }

    @Volatile private var recovered = false
    private val changes = MutableStateFlow(0L)
    val revisions = changes.asStateFlow()

    fun recipient(context: Context, conversation: Long): String? =
        ArchiveRuntime.get(context).archive.smsRecipient(conversation)

    fun canSend(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    fun schedule(context: Context, conversation: Long, body: String, sendAt: Long, to: String, sim: Int?): ScheduledMessage {
        require(canSend(context)) { "Allow SMS sending in Android settings first." }
        val saved = requireNotNull(recipient(context, conversation)) { NO_NUMBER }
        val address = CarrierSms.normalize(to)
        require(CarrierSms.normalize(saved) == address) { "The conversation's number changed. Review the recipient again." }
        SmsSubscriptions(context).resolve(sim)
        val m = scheduler(context).schedule(conversation, body, sendAt, smsAddress = address, smsSubscriptionId = sim)
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

    fun carrierReceipt(context: Context, uid: String): SmsSendReceipt? = CarrierSms.receipt(context, carrierKey(uid))

    private fun carrierKey(uid: String): String = "scheduled:$uid"

    /**
     * Transfers due texts to the carrier SMS journal. Returns how many were tracked. Calls are serialised.
     * A send in flight when the previous process died is turned into UNKNOWN once per process, before anything is claimed.
     */
    @Synchronized
    fun runDue(context: Context, scheduler: Scheduler = scheduler(context), archiveRecipient: (Long) -> String? = { recipient(context, it) }): Int {
        recover(scheduler)
        var submitted = 0
        for (m in scheduler.claimDue()) {
            val to = runCatching { archiveRecipient(m.conversation) }.getOrNull()
            if (to == null) {
                scheduler.markFailed(m.uid, NO_NUMBER)
                continue
            }
            if (m.smsAddress != null && m.smsAddress != CarrierSms.normalize(to)) {
                scheduler.markFailed(m.uid, "The conversation's number changed after scheduling.")
                continue
            }
            val result = runCatching { sender(context, to, m.body, m.smsSubscriptionId, carrierKey(m.uid)) }.getOrNull()
            when (result) {
                is SmsSubmission.Tracked -> {
                    scheduler.markSent(m.uid)
                    submitted++
                }
                is SmsSubmission.Refused -> scheduler.markFailed(m.uid, result.reason)
                null -> scheduler.markUnknown(m.uid, UNCERTAIN)
            }
        }
        changes.update { it + 1 }
        return submitted
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
