package com.victorious.sms.bridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.victorious.mail.Addr
import com.victorious.mail.AttachmentData
import com.victorious.mail.Draft
import com.victorious.mail.OutState
import com.victorious.sms.mail.MailRepository
import com.victorious.sms.sms.SmsRepository
import com.victorious.sms.ui.theme.Charcoal
import com.victorious.sms.ui.theme.Cyan
import com.victorious.sms.ui.theme.Magenta
import com.victorious.sms.ui.theme.VictorSMSTheme
import java.util.UUID

/** Wiring for the draft review prompt (notification + activity). */
object DraftReview {
    private const val CHANNEL = "desktop_drafts"
    fun ensureLoaded(ctx: Context) {
        runCatching { DraftHandoff.attachStore(EncryptedPendingStore(ctx.applicationContext, "handoff_drafts")) }.onFailure { DraftHandoff.attachStore(MemoryPendingStore()) }
    }

    fun notify(ctx: Context, d: IncomingDraft) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Drafts from your PC", NotificationManager.IMPORTANCE_HIGH))
        val i = Intent(ctx, ReviewDraftActivity::class.java).putExtra(ReviewDraftActivity.EXTRA_ID, d.draftId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, d.draftId.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("Draft from ${d.origin.ifBlank { d.caller.packageName }}")
            .setContentText("Review before anything is sent").setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(pi).setAutoCancel(true).build()
        if (Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) NotificationManagerCompat.from(ctx).notify(d.draftId.hashCode(), n)
        runCatching { ctx.startActivity(i) }      // foreground: opens right away; background: the notification does it
    }

    /** What "Send" does, for the identity the owner picked. Returns the final state to report. */
    fun submit(ctx: Context, d: IncomingDraft, accountId: String?): Pair<DraftState, String?> {
        if (d.channel == "sms") {
            val repo = SmsRepository(ctx); var ok = 0
            for (to in d.to) if (repo.send(to, d.body, com.victorious.comms.Author.BOT_APPROVED)) ok++
            return when (ok) { d.to.size -> DraftState.SUBMITTED to null; 0 -> DraftState.FAILED to "SMS could not be sent (permission or no service)"; else -> DraftState.FAILED to "only $ok of ${d.to.size} recipients were sent" }
        }
        val repo = MailRepository.get(ctx)
        val acct = accountId ?: return DraftState.FAILED to "no sending account is set up in VictorSMS"
        val atts = d.attachments.map { AttachmentData(it.first, it.second, Base64.decode(it.third, Base64.DEFAULT)) }
        val draft = Draft("handoff-${d.draftId}", acct, d.to.map { Addr(it) }, d.cc.map { Addr(it) }, d.bcc.map { Addr(it) }, d.subject, d.body, atts)
        val queued = repo.outbox.enqueue(draft)                                           // idempotent by id: a second Send tap cannot enqueue twice
        repo.flushOutbox()
        val item = repo.db.SqlOutboxStore().get(queued.draft.id)
        return when (item?.state) {
            OutState.SENT -> DraftState.SUBMITTED to null
            OutState.QUEUED -> DraftState.SUBMITTED to "queued: will be sent when the connection is back"
            OutState.UNCERTAIN -> DraftState.FAILED to "connection lost during sending; it may have been delivered — check Sent before trying again"
            else -> DraftState.FAILED to (item?.detail ?: "not accepted by the mail server")
        }
    }
}

/** Owner review: recipients, sending identity, contents. Only the Send button here submits. Not exported. */
class ReviewDraftActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DraftReview.ensureLoaded(this)
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val d = DraftHandoff.find(id)
        val accounts = MailRepository.get(this).storedAccounts()
        setContent {
            VictorSMSTheme {
                Column(Modifier.fillMaxSize().background(Charcoal).safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (d == null || d.state != DraftState.RECEIVED) { Text(if (d == null) "This draft is no longer available." else "This draft was already ${d.state.name.lowercase()}.", color = Magenta); Button(onClick = { finish() }) { Text("Close") }; return@Column }
                    Text(if (d.channel == "sms") "Review SMS from your PC" else "Review email from your PC", color = Cyan, fontWeight = FontWeight.Bold)
                    Text("Prepared by: ${d.origin.ifBlank { "unknown" }} via ${d.caller.packageName}", color = MaterialTheme.colorScheme.onBackground)
                    var pick by remember { mutableStateOf(accounts.firstOrNull { a -> d.accountHint?.equals(a.address, true) == true }?.id ?: accounts.firstOrNull()?.id) }
                    if (d.channel == "email") {
                        Text("Send as:", color = Cyan)
                        if (accounts.isEmpty()) Text("No mail account is set up in VictorSMS. Add one in Settings → Email accounts first.", color = Magenta)
                        accounts.forEach { a -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = pick == a.id, onClick = { pick = a.id }); Text("${a.displayName} <${a.address}>", color = MaterialTheme.colorScheme.onBackground) } }
                    } else Text("Send as: this phone's SMS (default SIM)", color = MaterialTheme.colorScheme.onBackground)
                    Text("To: ${d.to.joinToString()}", color = MaterialTheme.colorScheme.onBackground)
                    if (d.cc.isNotEmpty()) Text("Cc: ${d.cc.joinToString()}", color = MaterialTheme.colorScheme.onBackground)
                    if (d.bcc.isNotEmpty()) Text("Bcc: ${d.bcc.joinToString()}", color = MaterialTheme.colorScheme.onBackground)
                    if (d.channel == "email") Text("Subject: ${d.subject}", color = MaterialTheme.colorScheme.onBackground)
                    Text(d.body, color = MaterialTheme.colorScheme.onBackground)
                    d.attachments.forEach { Text("Attachment: ${it.first} (${it.second})", color = Cyan) }
                    Text("Nothing is sent unless you tap Send. Declining sends nothing.", color = Cyan)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(enabled = d.channel == "sms" || pick != null, onClick = {
                            val acct = pick
                            Thread { DraftHandoff.approve(d.draftId, System.currentTimeMillis()) { x -> DraftReview.submit(applicationContext, x, acct) }; NotificationManagerCompat.from(this@ReviewDraftActivity).cancel(d.draftId.hashCode()); runOnUiThread { finish() } }.start()
                        }) { Text("Send") }
                        OutlinedButton(onClick = { DraftHandoff.decline(d.draftId); NotificationManagerCompat.from(this@ReviewDraftActivity).cancel(d.draftId.hashCode()); finish() }) { Text("Decline", color = Magenta) }
                    }
                }
            }
        }
    }
    companion object { const val EXTRA_ID = "draftId" }
}
