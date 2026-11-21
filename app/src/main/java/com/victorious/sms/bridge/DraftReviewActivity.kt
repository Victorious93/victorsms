package com.victorious.sms.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import com.victorious.sms.inbox.HandoffState
import com.victorious.sms.mail.MailRuntime
import com.victorious.sms.ui.theme.Charcoal
import com.victorious.sms.ui.theme.Cyan
import com.victorious.sms.ui.theme.Magenta
import com.victorious.sms.ui.theme.VictorSMSTheme
import kotlin.concurrent.thread

/**
 * Owner review of a draft prepared on VictorLinux. Shows recipients, the sending identity and the contents.
 * The Send button below is the only code path that can submit it. Not exported.
 */
class DraftReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_DRAFT_ID).orEmpty()
        val rt = MailRuntime.get(applicationContext)
        val identity = rt.accounts().firstOrNull()?.address.orEmpty()
        setContent {
            VictorSMSTheme {
                var result by remember { mutableStateOf<HandoffState?>(null) }
                var chosen by remember { mutableStateOf(identity) }
                val review = rt.handoff.review(id, chosen)
                Column(Modifier.fillMaxSize().background(Charcoal).safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (review == null || result != null) {
                        Text(when (result) {
                            HandoffState.SUBMITTED -> "Sent."
                            HandoffState.DECLINED -> "Declined. Nothing was sent."
                            HandoffState.FAILED -> "Not sent. Nothing left the phone."
                            HandoffState.UNCERTAIN -> "The connection broke while sending. It may have been delivered. Check before resending."
                            else -> "This draft is no longer waiting."
                        }, color = Magenta)
                        Button(onClick = { finish() }) { Text("Close") }
                        return@Column
                    }
                    Text("Draft from your PC", color = Cyan, fontWeight = FontWeight.Bold)
                    Text(if (review.kind == "sms") "Text message (SMS)" else "Email", color = MaterialTheme.colorScheme.onBackground)
                    review.recipients.forEach { Text(it, color = MaterialTheme.colorScheme.onBackground) }
                    if (review.kind == "email") {
                        Text("Send from", color = Cyan)
                        rt.accounts().forEach { a ->
                            OutlinedButton(onClick = { chosen = a.address }) { Text((if (a.address == chosen) "● " else "") + a.address) }
                        }
                        Text("Subject: ${review.subject}", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
                    } else Text("Send from: this phone's SMS", color = MaterialTheme.colorScheme.onBackground)
                    Text(review.body, color = MaterialTheme.colorScheme.onBackground)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            thread { val r = rt.handoff.ownerSend(id, chosen); runOnUiThread { result = r } }
                        }) { Text("Send") }
                        OutlinedButton(onClick = { result = rt.handoff.ownerDecline(id) }) { Text("Decline", color = Magenta) }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_DRAFT_ID = "draftId"
        private const val CHANNEL = "desktop_drafts"
        fun notifyReview(context: Context, draftId: String) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Drafts from your PC", NotificationManager.IMPORTANCE_HIGH))
            val i = Intent(context, DraftReviewActivity::class.java).putExtra(EXTRA_DRAFT_ID, draftId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(context, draftId.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(draftId.hashCode(), NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("A draft is waiting for your review").setContentText("Open it to review and send").setContentIntent(pi).setAutoCancel(true).build())
        }
    }
}
