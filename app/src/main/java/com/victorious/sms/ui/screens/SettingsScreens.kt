package com.victorious.sms.ui.screens

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
fun SettingsScreen(onConnectedApps: () -> Unit, onBack: () -> Unit) {
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
