package com.victorious.sms.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victorious.sms.sms.SmsMessage
import com.victorious.sms.sms.SmsRepository
import com.victorious.sms.ui.theme.Charcoal
import com.victorious.sms.ui.theme.CharcoalSurface
import com.victorious.sms.ui.theme.Cyan
import com.victorious.sms.ui.theme.Magenta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One SMS thread (threadId >= 0) or a new message to [address] (threadId < 0). */
@Composable
fun SmsThreadScreen(threadId: Long, address: String, initialBody: String = "", onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { SmsRepository(ctx) }
    val scope = rememberCoroutineScope()
    var to by remember { mutableStateOf(address) }
    var msgs by remember { mutableStateOf(emptyList<SmsMessage>()) }
    var draft by remember { mutableStateOf(initialBody) }
    var status by remember { mutableStateOf<String?>(null) }
    val twilio = remember { com.victorious.sms.twilio.TwilioCredentials(ctx) }
    var identity by remember(to) { mutableStateOf(twilio.resolve(to)) }
    var aliasMsgs by remember { mutableStateOf(emptyList<SmsMessage>()) }
    var pickIdentity by remember { mutableStateOf(false) }
    var mediaUrl by remember { mutableStateOf("") }
    // Paid action pending confirmation: (title, price text, action)
    var confirm by remember { mutableStateOf<Triple<String, String?, suspend () -> String>?>(null) }
    LaunchedEffect(threadId) {
        while (threadId >= 0) { msgs = withContext(Dispatchers.IO) { repo.messages(threadId) }; delay(3000) }
    }
    // Alias messages with this person (both directions), fetched from Twilio. Never logged.
    LaunchedEffect(to, twilio.aliases) {
        while (twilio.isSet() && twilio.aliases.isNotEmpty() && to.isNotBlank()) {
            aliasMsgs = withContext(Dispatchers.IO) {
                runCatching {
                    val c = com.victorious.sms.twilio.TwilioClient(twilio)
                    twilio.aliases.flatMap { a -> c.aliasMessages(a, peer = com.victorious.sms.twilio.PhoneNumbers.normalize(to)) }
                        .map { SmsMessage(-(it.dateMs + it.sid.hashCode()), it.peer, "[alias …${it.alias.takeLast(4)}] " + it.body +
                            (if (it.numMedia > 0) " (+${it.numMedia} media)" else ""), it.dateMs, it.outgoing) }
                }.getOrDefault(aliasMsgs)
            }
            delay(30_000)
        }
    }
    val shown = (msgs + aliasMsgs).distinctBy { it.id }.sortedBy { it.dateMs }
    Column(Modifier.fillMaxSize().background(Charcoal).safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (threadId >= 0) to else "New SMS", color = Cyan, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            if (threadId >= 0) {
                TextButton(onClick = {
                    com.victorious.sms.block.Blocklist(ctx).set(to, com.victorious.sms.block.BlockAction.BLOCK)
                    status = "Blocked $to. Manage in Settings → Blocking & spam."
                }) { Text("Block", color = Magenta) }
            }
            TextButton(onClick = onBack) { Text("Back", color = Cyan) }
        }
        if (threadId < 0) {
            OutlinedTextField(to, { to = it }, label = { Text("To") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true) {
            items(shown.asReversed(), key = { it.id }) { m ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start) {
                    Text(m.body, color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.background(CharcoalSurface, RoundedCornerShape(12.dp)).padding(10.dp))
                }
            }
        }
        status?.let { Text(it, color = Magenta, modifier = Modifier.padding(horizontal = 12.dp)) }
        AssistBar(to, { msgs.map { it.outgoing to it.body } }, draft) { draft = it }
        // Identity in use, always visible. Tap to change for this thread.
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sending as: " + com.victorious.sms.twilio.IdentityResolver.label(identity),
                color = if (identity is com.victorious.sms.twilio.Identity.Alias) Magenta else Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            if (twilio.isSet() && twilio.aliases.isNotEmpty()) {
                TextButton(onClick = { pickIdentity = true }) { Text("Change", color = Cyan, fontSize = 12.sp) }
                val id = identity
                if (id is com.victorious.sms.twilio.Identity.Alias && to.isNotBlank()) {
                    TextButton(onClick = {
                        val dest = com.victorious.sms.twilio.PhoneNumbers.normalize(to)
                        if (twilio.myPhone.isBlank()) { status = "Set your real phone number in Settings → Twilio alias number first."; return@TextButton }
                        scope.launch {
                            val price = withContext(Dispatchers.IO) { runCatching { com.victorious.sms.twilio.TwilioClient(twilio).voicePricePerMinute(dest) }.getOrNull() }
                            confirm = Triple("Masked call from alias …${id.number.takeLast(4)}?", price?.let { "about $it per leg (2 legs: your phone + the callee)" }) {
                                com.victorious.sms.twilio.TwilioClient(twilio).bridgeCall(id.number, twilio.myPhone, dest)
                                "Calling your phone; answer to connect. They will see your alias."
                            }
                        }
                    }) { Text("Masked call", color = Magenta, fontSize = 12.sp) }
                }
            }
        }
        if (identity is com.victorious.sms.twilio.Identity.Alias) {
            OutlinedTextField(mediaUrl, { mediaUrl = it.trim() }, label = { Text("MMS image URL (public https, optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(draft, { draft = it }, placeholder = { Text("Text message") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.padding(4.dp))
            Button(enabled = (draft.isNotBlank() || mediaUrl.isNotBlank()) && to.isNotBlank(), onClick = {
                val text = draft; val dest = to; val id = identity; val media = mediaUrl.ifBlank { null }
                scope.launch {
                    if (id is com.victorious.sms.twilio.Identity.Alias) {
                        // Paid: confirm with price first.
                        val price = withContext(Dispatchers.IO) { runCatching { com.victorious.sms.twilio.TwilioClient(twilio).smsPrice("US") }.getOrNull() }
                        confirm = Triple(if (media != null) "Send MMS from alias …${id.number.takeLast(4)}?" else "Send SMS from alias …${id.number.takeLast(4)}?",
                            price?.let { if (media != null) "SMS rate about $it; MMS is billed at Twilio's (higher) MMS rate" else "about $it" }) {
                            com.victorious.sms.twilio.TwilioClient(twilio).sendMessage(id.number, com.victorious.sms.twilio.PhoneNumbers.normalize(dest), text, media)
                            draft = ""; mediaUrl = ""; "Sent via Twilio from your alias."
                        }
                    } else {
                        val ok = withContext(Dispatchers.IO) { repo.send(dest, text) }
                        if (ok) { draft = ""; status = null } else status = "Not sent: SMS permission missing or send failed."
                    }
                }
            }) { Text("Send") }
        }
    }

    if (pickIdentity) {
        val options = listOf(com.victorious.sms.twilio.Identity.Sim) + twilio.aliases.sorted().map { com.victorious.sms.twilio.Identity.Alias(it) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pickIdentity = false },
            title = { Text("Send this thread as") },
            text = {
                Column {
                    options.forEach { o ->
                        TextButton(onClick = { twilio.setThreadIdentity(to, o); identity = twilio.resolve(to); pickIdentity = false }) {
                            Text((if (o == identity) "● " else "○ ") + com.victorious.sms.twilio.IdentityResolver.label(o))
                        }
                    }
                    TextButton(onClick = { twilio.setThreadIdentity(to, null); identity = twilio.resolve(to); pickIdentity = false }) {
                        Text("Use default (" + com.victorious.sms.twilio.IdentityResolver.label(twilio.defaultIdentity) + ")")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickIdentity = false }) { Text("Close") } },
        )
    }
    confirm?.let { (title, price, action) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text("This is charged to your Twilio account: " + (price ?: "price could not be loaded (see twilio.com/pricing)") +
                ". Nothing is sent or dialled unless you tap Confirm.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch { status = runCatching { withContext(Dispatchers.IO) { action() } }.getOrElse { "Failed: " + (it.message ?: it.javaClass.simpleName) } }
                }) { Text("Confirm", color = Magenta) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
