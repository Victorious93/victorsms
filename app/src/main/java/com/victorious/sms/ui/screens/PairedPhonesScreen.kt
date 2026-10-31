package com.victorious.sms.ui.screens

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victorious.sms.relay.PairedPhone
import com.victorious.sms.relay.RelayCrypto
import com.victorious.sms.relay.RelayNode
import com.victorious.sms.relay.RelayService
import com.victorious.sms.ui.theme.Charcoal
import com.victorious.sms.ui.theme.Cyan
import com.victorious.sms.ui.theme.Magenta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun PairedPhonesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val node = remember { RelayNode.get(ctx) }
    val prefs = remember { ctx.getSharedPreferences("relay_settings", 0) }
    val scope = rememberCoroutineScope()
    var relayOn by remember { mutableStateOf(prefs.getBoolean("relayOn", false)) }
    var phones by remember { mutableStateOf(node.phones.all()) }
    val candidate by node.candidate.collectAsState()
    var peerAddr by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<String?>(null) }
    val myFp = remember { runCatching { RelayCrypto.myFingerprint() }.getOrDefault("unavailable") }
    val myAddrs = remember {
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .filter { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                .map { "${it.hostAddress}:${RelayNode.DEFAULT_PORT}" }
        }.getOrDefault(emptyList())
    }
    fun save(p: PairedPhone) { node.phones.save(p); phones = node.phones.all() }

    Column(Modifier.fillMaxSize().background(Charcoal).safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Paired phones", color = Cyan, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back", color = Cyan) }
        }
        ToggleRow("Phone relay (listen for paired phones). Off by default.", relayOn) { on ->
            relayOn = on; prefs.edit().putBoolean("relayOn", on).apply()
            val i = Intent(ctx, RelayService::class.java)
            if (on) { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) } else ctx.stopService(i)
        }
        Text("This phone: " + myAddrs.joinToString().ifBlank { "no IPv4 address" }, color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp)
        Text("Key fingerprint: " + myFp.chunked(4).joinToString(" "), color = Cyan.copy(alpha = 0.6f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)

        Text("Pair a new phone", color = Cyan, fontWeight = FontWeight.SemiBold)
        Text("On the other phone: turn Phone relay on and tap Wait for pairing. Here: enter its mesh address and tap Pair. Both phones then show a 6-digit code; confirm only if they match.",
            color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { node.startPairingWindow(); info = "Waiting for pairing for 5 minutes…" }) { Text("Wait for pairing") }
        }
        OutlinedTextField(peerAddr, { peerAddr = it }, label = { Text("Peer address (host:port)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = peerAddr.isNotBlank(), onClick = {
            node.startPairingWindow()
            scope.launch {
                info = withContext(Dispatchers.IO) {
                    runCatching {
                        val r = node.request(peerAddr.trim(), "relay.pair.hello",
                            node.envelope().put("name", Build.MODEL).put("address", myAddrs.firstOrNull().orEmpty()))
                        if (r.optBoolean("ok")) "Compare the code below with the other phone." else "Pairing refused: " + r.optString("error")
                    }.getOrElse { "Could not connect: ${it.javaClass.simpleName}" }
                }
            }
        }) { Text("Pair") }
        candidate?.let { c ->
            Text("Pairing code: ${c.code}", color = Magenta, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("With ${c.name} (${c.address})", color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { node.confirmCandidate(); phones = node.phones.all(); info = "Paired. All permissions are off." }) { Text("Codes match") }
                TextButton(onClick = { node.rejectCandidate(); info = "Pairing cancelled." }) { Text("Cancel", color = Magenta) }
            }
        }
        info?.let { Text(it, color = Cyan, fontSize = 12.sp) }

        phones.forEach { p ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(p.name, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
                Text(p.address + "  ·  " + p.fingerprint.take(16), color = Cyan.copy(alpha = 0.6f), fontSize = 10.sp)
                ToggleRow("Let it ask this phone to send SMS (each send needs approval)", p.allowSmsSend) { save(p.copy(allowSmsSend = it)) }
                ToggleRow("Let it ask this phone to place calls (each call needs approval)", p.allowCalls) { save(p.copy(allowCalls = it)) }
                ToggleRow("Mirror my incoming texts to it", p.mirrorInboxToPeer) { save(p.copy(mirrorInboxToPeer = it)) }
                RelayActions(node, p)
                TextButton(onClick = { node.phones.revoke(p.fingerprint); phones = node.phones.all() }) { Text("Revoke pairing", color = Magenta) }
            }
        }
    }
}

/** Ask the paired phone to send an SMS or place a call through its SIM. */
@Composable
private fun RelayActions(node: RelayNode, p: PairedPhone) {
    val scope = rememberCoroutineScope()
    var to by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    fun poll(statusOp: String, requestId: String) = scope.launch(Dispatchers.IO) {
        repeat(40) {
            delay(3000)
            val r = runCatching { node.request(p, statusOp, node.envelope().put("requestId", requestId)) }.getOrNull()
            val st = r?.optJSONObject("result")?.optString("status") ?: r?.optString("error") ?: "unreachable"
            status = "Status: $st"
            if (st != "pending") return@launch
        }
    }
    fun send(op: String, statusOp: String, params: org.json.JSONObject) = scope.launch(Dispatchers.IO) {
        val r = runCatching { node.request(p, op, params) }.getOrElse { status = "Could not reach phone"; return@launch }
        if (r.optBoolean("ok")) { status = "Waiting for approval on ${p.name}…"; poll(statusOp, params.getString("requestId")) }
        else status = "Refused: " + r.optString("error")
    }
    OutlinedTextField(to, { to = it }, label = { Text("Number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(body, { body = it }, label = { Text("Text (for SMS)") }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = to.isNotBlank() && body.isNotBlank(), onClick = {
            send("relay.sms.send", "relay.sms.send.status", node.envelope().put("to", to).put("body", body))
        }) { Text("Send via ${p.name}") }
        Button(enabled = to.isNotBlank(), onClick = {
            send("relay.call.place", "relay.call.status", node.envelope().put("to", to))
        }) { Text("Call via") }
    }
    status?.let { Text(it, color = Cyan, fontSize = 12.sp) }
}
