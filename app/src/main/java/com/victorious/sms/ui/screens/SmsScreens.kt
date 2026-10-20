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
    var viaAlias by remember { mutableStateOf(false) }
    LaunchedEffect(threadId) {
        while (threadId >= 0) { msgs = withContext(Dispatchers.IO) { repo.messages(threadId) }; delay(3000) }
    }
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
            items(msgs.asReversed(), key = { it.id }) { m ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start) {
                    Text(m.body, color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.background(CharcoalSurface, RoundedCornerShape(12.dp)).padding(10.dp))
                }
            }
        }
        status?.let { Text(it, color = Magenta, modifier = Modifier.padding(horizontal = 12.dp)) }
        AssistBar(to, { msgs.map { it.outgoing to it.body } }, draft) { draft = it }
        if (twilio.isSet() && twilio.aliasNumber.isNotBlank()) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(checked = viaAlias, onCheckedChange = { viaAlias = it })
                Text("Send from alias ${twilio.aliasNumber} (Twilio)", color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(draft, { draft = it }, placeholder = { Text("Text message") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.padding(4.dp))
            Button(enabled = draft.isNotBlank() && to.isNotBlank(), onClick = {
                val text = draft; val dest = to
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        if (viaAlias) runCatching { com.victorious.sms.twilio.TwilioClient(twilio).sendSms(twilio.aliasNumber, dest, text) }.isSuccess
                        else repo.send(dest, text)
                    }
                    if (ok) { draft = ""; status = null } else status = "Not sent: SMS permission missing or send failed."
                }
            }) { Text("Send") }
        }
    }
}
