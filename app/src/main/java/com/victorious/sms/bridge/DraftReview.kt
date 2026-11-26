package com.victorious.sms.bridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.victorious.comms.Providers
import com.victorious.mail.Addr
import com.victorious.mail.AttachmentData
import com.victorious.mail.Draft
import com.victorious.mail.OutState
import com.victorious.sms.archive.ArchiveRuntime
import com.victorious.sms.mail.MailRepository
import com.victorious.sms.mail.StoredAccount
import com.victorious.sms.sms.SmsRepository
import com.victorious.sms.ui.theme.Cyan
import com.victorious.sms.ui.theme.Magenta
import com.victorious.sms.ui.theme.VictorSMSTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Wiring for the draft review prompt (notification + activity). */
object DraftReview {
    private const val CHANNEL = "desktop_drafts"
    private var loaded = false

    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (!loaded) {
            DraftHandoff.attachStore(EncryptedPendingStore(ctx.applicationContext, "handoff_drafts", durableWrites = true))
            loaded = true
        }
    }

    fun intent(ctx: Context, d: IncomingDraft): Intent =
        Intent(ctx, ReviewDraftActivity::class.java)
            .setData(Uri.Builder().scheme("victorsms").authority("draft")
                .appendPath(d.caller.packageName).appendPath(d.draftId).build())
            .putExtra(ReviewDraftActivity.EXTRA_ID, d.draftId)
            .putExtra(ReviewDraftActivity.EXTRA_CALLER, d.caller.packageName)

    fun notificationId(d: IncomingDraft): Int = DraftHandoff.key(d.caller, d.draftId).hashCode()

    fun notify(ctx: Context, d: IncomingDraft) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Drafts to review", NotificationManager.IMPORTANCE_HIGH))
        val i = intent(ctx, d).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, notificationId(d), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("Draft from ${d.origin.ifBlank { d.caller.packageName }}")
            .setContentText("Review before anything is sent").setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(pi).setAutoCancel(true).build()
        if (Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(ctx).notify(notificationId(d), n)
        }
        runCatching { ctx.startActivity(i) }
    }

    /** What "Send" does, for the identity the owner picked. Returns the final state to report. */
    fun submit(ctx: Context, d: IncomingDraft, accountId: String?, body: String): Pair<DraftState, String?> {
        val op = if (d.conversation == null) DraftCapability.OP_DELIVER else DraftCapability.OP_FROM_ARCHIVE
        val authorization = PairingPolicy.decide(
            BridgeSettings(ctx).externalControlEnabled, listOf(d.caller), PairingRegistry(ctx).all(), op,
        ).decision
        if (authorization !is AuthDecision.Allowed) return DraftState.DECLINED to "The app's draft permission was removed."
        d.conversation?.let { uid ->
            val archive = ArchiveRuntime.get(ctx).archive
            val current = archive.conversationByUid(uid)?.takeIf { it.assistantAccess }
            if (current == null || archive.smsRecipient(current.id) != d.to.singleOrNull()) {
                return DraftState.DECLINED to "The saved conversation or its SMS recipient changed."
            }
        }
        if (d.channel == "sms") {
            if (body.isBlank()) return DraftState.FAILED to "Enter a text message first."
            val repo = SmsRepository(ctx); var ok = 0
            for (to in d.to) if (repo.send(to, body, com.victorious.comms.Author.BOT_APPROVED)) ok++
            return when (ok) {
                d.to.size -> DraftState.SUBMITTED to "Submitted to Android. Carrier delivery is unconfirmed."
                0 -> DraftState.UNKNOWN to "Check the SMS conversation before sending again."
                else -> DraftState.UNKNOWN to "Android accepted $ok of ${d.to.size} submissions. Check the conversations before sending again."
            }
        }
        val repo = MailRepository.get(ctx)
        val acct = accountId ?: return DraftState.FAILED to "no sending account is set up in VictorSMS"
        val atts = d.attachments.map { AttachmentData(it.first, it.second, Base64.decode(it.third, Base64.DEFAULT)) }
        val draft = Draft("handoff-${d.draftId}", acct, d.to.map { Addr(it) }, d.cc.map { Addr(it) }, d.bcc.map { Addr(it) }, d.subject, body, atts)
        val queued = repo.outbox.enqueue(draft)                                           // idempotent by id: a second Send tap cannot enqueue twice
        repo.flushOutbox()
        val item = repo.db.SqlOutboxStore().get(queued.draft.id)
        return when (item?.state) {
            OutState.SENT -> DraftState.SUBMITTED to null
            OutState.QUEUED -> DraftState.SUBMITTED to "queued: will be sent when the connection is back"
            OutState.UNCERTAIN -> DraftState.UNKNOWN to "Connection was lost during submission. Check Sent before trying again."
            else -> DraftState.FAILED to (item?.detail ?: "not accepted by the mail server")
        }
    }
}

@Composable
fun PendingDraftsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var drafts by remember { mutableStateOf<List<IncomingDraft>?>(null) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.IO) { DraftReview.ensureLoaded(context) }
            DraftHandoff.revisions.collect { drafts = DraftHandoff.pending() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = true
        }
    }
    Column(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Drafts to review", color = colors.onBackground, style = MaterialTheme.typography.titleLarge)
        }
        val pending = drafts
        when {
            error -> Text("The draft inbox could not be opened.", color = MaterialTheme.colorScheme.error)
            pending == null -> Text("Loading drafts…", color = colors.onBackground)
            pending.isEmpty() -> Text("There are no drafts waiting for review.", color = colors.onBackground)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(pending, key = { DraftHandoff.key(it.caller, it.draftId) }) { draft ->
                    Column {
                        Text(draft.origin.ifBlank { draft.caller.packageName }, color = colors.onBackground, fontWeight = FontWeight.Bold)
                        Text("To ${draft.to.joinToString()}", color = colors.onBackground)
                        OutlinedButton(onClick = { context.startActivity(DraftReview.intent(context, draft)) }) { Text("Review") }
                    }
                }
            }
        }
    }
}

/** Reviews a stored draft and submits only after the owner presses Send. */
class ReviewDraftActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val caller = intent.getStringExtra(EXTRA_CALLER)
        setContent {
            VictorSMSTheme {
                val scope = rememberCoroutineScope()
                val colors = MaterialTheme.colorScheme
                var draft by remember { mutableStateOf<IncomingDraft?>(null) }
                var accounts by remember { mutableStateOf(emptyList<StoredAccount>()) }
                var loading by remember { mutableStateOf(true) }
                var busy by remember { mutableStateOf(false) }
                var outcome by remember { mutableStateOf<DraftState?>(null) }
                var status by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(id, caller) {
                    try {
                        val loaded = withContext(Dispatchers.IO) {
                            DraftReview.ensureLoaded(applicationContext)
                            val d = DraftHandoff.find(id, caller)
                            if (d != null) DraftHandoff.status(d.caller, d.draftId)
                            d to if (d?.channel == "email") MailRepository.get(applicationContext).storedAccounts() else emptyList()
                        }
                        draft = loaded.first
                        accounts = loaded.second
                        outcome = loaded.first?.state
                        status = loaded.first?.detail
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        status = "The draft could not be opened."
                    } finally {
                        loading = false
                    }
                }
                Column(
                    Modifier.fillMaxSize().background(colors.background).safeDrawingPadding().imePadding()
                        .padding(20.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val d = draft
                    if (loading) {
                        Text("Loading draft…", color = colors.onBackground)
                        return@Column
                    }
                    if (d == null || outcome != DraftState.RECEIVED) {
                        val message = status ?: when (outcome) {
                            DraftState.SUBMITTED -> "Draft submitted."
                            DraftState.DECLINED -> "Draft declined."
                            DraftState.UNKNOWN -> "Submission outcome unknown."
                            DraftState.FAILED -> "Submission failed."
                            else -> "This draft is no longer available."
                        }
                        Text(message, color = colors.onBackground)
                        Button(onClick = { finish() }) { Text("Close") }
                        return@Column
                    }
                    Text(if (d.channel == "sms") "Review SMS draft" else "Review email draft", color = Cyan, fontWeight = FontWeight.Bold)
                    Text("${d.origin.ifBlank { "Draft" }} via ${d.caller.packageName}", color = MaterialTheme.colorScheme.onBackground)
                    var body by rememberSaveable(d.draftId) { mutableStateOf(d.body) }
                    val maxChars = if (d.conversation != null) Providers.of("sms").maxBodyChars else 100_000
                    var pick by rememberSaveable(d.draftId) {
                        mutableStateOf(accounts.firstOrNull { a -> d.accountHint?.equals(a.address, true) == true }?.id ?: accounts.firstOrNull()?.id)
                    }
                    if (d.channel == "email") {
                        Text("Send as:", color = Cyan)
                        if (accounts.isEmpty()) Text("No mail account is set up in VictorSMS. Add one in Settings → Email accounts first.", color = Magenta)
                        accounts.forEach { a -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = pick == a.id, onClick = { pick = a.id }); Text("${a.displayName} <${a.address}>", color = MaterialTheme.colorScheme.onBackground) } }
                    } else Text("Send as: this phone's SMS (default SIM)", color = MaterialTheme.colorScheme.onBackground)
                    Text("To: ${d.to.joinToString()}", color = MaterialTheme.colorScheme.onBackground)
                    if (d.cc.isNotEmpty()) Text("Cc: ${d.cc.joinToString()}", color = MaterialTheme.colorScheme.onBackground)
                    if (d.bcc.isNotEmpty()) Text("Bcc: ${d.bcc.joinToString()}", color = MaterialTheme.colorScheme.onBackground)
                    if (d.channel == "email") Text("Subject: ${d.subject}", color = MaterialTheme.colorScheme.onBackground)
                    OutlinedTextField(
                        value = body, onValueChange = { if (it.length <= maxChars) body = it },
                        label = { Text("Message") }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    )
                    d.attachments.forEach { Text("Attachment: ${it.first} (${it.second})", color = Cyan) }
                    status?.let { Text(it, color = Magenta) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(enabled = !busy && if (d.channel == "sms") body.isNotBlank() else pick != null, onClick = {
                            val acct = pick
                            val text = body
                            busy = true
                            scope.launch {
                                try {
                                    outcome = withContext(Dispatchers.IO) {
                                        DraftHandoff.approve(d.draftId, System.currentTimeMillis(), d.caller.packageName) { x ->
                                            DraftReview.submit(applicationContext, x, acct, text)
                                        }
                                    }
                                    status = d.detail
                                    NotificationManagerCompat.from(this@ReviewDraftActivity).cancel(DraftReview.notificationId(d))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    status = "The draft could not be submitted. Check the conversation before trying again."
                                } finally {
                                    busy = false
                                }
                            }
                        }) { Text("Send") }
                        OutlinedButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { DraftHandoff.decline(d.draftId, d.caller.packageName) }
                                    NotificationManagerCompat.from(this@ReviewDraftActivity).cancel(DraftReview.notificationId(d))
                                    finish()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    status = "The draft could not be declined."
                                } finally {
                                    busy = false
                                }
                            }
                        }) { Text("Decline", color = Magenta) }
                    }
                }
            }
        }
    }
    companion object {
        const val EXTRA_ID = "draftId"
        const val EXTRA_CALLER = "caller"
    }
}
