package com.victorious.sms.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victorious.sms.bridge.BridgeSettings
import com.victorious.sms.bridge.OpGroup
import com.victorious.sms.bridge.PairedApp
import com.victorious.sms.bridge.PairingRegistry
import com.victorious.sms.ui.theme.Charcoal
import com.victorious.sms.ui.theme.Cyan
import com.victorious.sms.ui.theme.Magenta

@Composable
fun SettingsScreen(onConnectedApps: () -> Unit, onPairedPhones: () -> Unit = {}, extra: List<Pair<String, () -> Unit>> = emptyList(), onBack: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { BridgeSettings(ctx) }
    var ext by remember { mutableStateOf(settings.externalControlEnabled) }
    Column(Modifier.fillMaxSize().background(Charcoal).safeDrawingPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", color = Cyan, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back", color = Cyan) }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("External control", color = MaterialTheme.colorScheme.onBackground)
                Text("Lets approved fleet apps use VictorSMS. Off by default.", color = Cyan.copy(alpha = 0.6f), fontSize = 12.sp)
            }
            Switch(checked = ext, onCheckedChange = { ext = it; settings.externalControlEnabled = it })
        }
        Text("Connected apps", color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onConnectedApps).padding(vertical = 12.dp))
        Text("Paired phones", color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onPairedPhones).padding(vertical = 12.dp))
        extra.forEach { (label, go) ->
            Text(label, color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth().clickable(onClick = go).padding(vertical = 12.dp))
        }
    }
}

@Composable
fun ConnectedAppsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val registry = remember { PairingRegistry(ctx) }
    var apps by remember { mutableStateOf(registry.all()) }
    fun save(a: PairedApp) { registry.set(a); apps = registry.all() }
    Column(Modifier.fillMaxSize().background(Charcoal).safeDrawingPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Connected apps", color = Cyan, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back", color = Cyan) }
        }
        if (apps.isEmpty()) {
            Text("No app has asked to connect yet. Apps appear here after their first request.",
                color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(vertical = 12.dp))
        }
        LazyColumn {
            items(apps, key = { it.packageName + it.certSha256 }) { a ->
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text(a.packageName, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
                    Text("Cert SHA-256 " + a.certSha256, color = Cyan.copy(alpha = 0.6f), fontSize = 10.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (a.approved) "Approved" else "Not approved", color = if (a.approved) Cyan else Magenta,
                            modifier = Modifier.weight(1f))
                        Switch(checked = a.approved, onCheckedChange = {
                            save(if (it) a.copy(approved = true) else a.copy(approved = false, groups = emptySet()))
                        })
                    }
                    OpGroup.entries.forEach { g ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = g in a.groups, enabled = a.approved, onCheckedChange = { on ->
                                save(a.copy(groups = if (on) a.groups + g else a.groups - g))
                            })
                            Text(
                                when (g) {
                                    OpGroup.READ -> "Read-only: status and threads"
                                    OpGroup.SEND -> "Send (each send still needs your confirmation)"
                                    OpGroup.DRAFT -> "Drafts: show drafts from your PC for your review (never sends by itself)"
                                },
                                color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp,
                            )
                        }
                    }
                    TextButton(onClick = { registry.forget(a); apps = registry.all() }) { Text("Forget", color = Magenta) }
                }
            }
        }
    }
}

/** Own SIP server: paste the provisioning JSON printed by `victor-voip add-device`. Strictly TLS + SRTP + pinned certificate. */
@androidx.compose.runtime.Composable
fun SipSettingsScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val store = androidx.compose.runtime.remember { com.victorious.sms.voip.SipStore(ctx) }
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var msg by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var cfg by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(store.load()) }
    val status by com.victorious.sms.voip.SipCalls.status.collectAsState()
    Page("SIP / VoIP", actions = { TextButton(onClick = onBack) { Text("Back", color = Cyan) } }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Calls over your own server (victor-voip). Only TLS with required SRTP and a pinned server certificate is accepted.", color = MaterialTheme.colorScheme.onBackground)
            cfg?.let { Text("Account ${it.username} @ ${it.domain}:${it.port}\nStatus: $status", color = Cyan) }
            if (!com.victorious.sms.voip.SipCalls.engineAvailable) Text("This build has no VoIP engine (Linphone build required), so calls cannot be placed yet.", color = Magenta)
            OutlinedTextField(text, { text = it }, label = { Text("Provisioning JSON") }, minLines = 5, modifier = Modifier.fillMaxWidth())
            msg?.let { Text(it, color = Magenta) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = text.isNotBlank(), onClick = {
                    store.save(text).onSuccess { cfg = it; text = ""; msg = null; com.victorious.sms.phone.SipConnectionService.register(ctx); com.victorious.sms.voip.SipCalls.start(ctx) }.onFailure { msg = "Refused: ${it.message}" }
                }) { Text("Save and register") }
                if (cfg != null) OutlinedButton(onClick = { com.victorious.sms.voip.SipCalls.stop(); store.clear(); cfg = null }) { Text("Remove", color = Magenta) }
            }
        }
    }
}
