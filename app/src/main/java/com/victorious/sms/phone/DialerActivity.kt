package com.victorious.sms.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victorious.sms.ui.theme.Charcoal
import com.victorious.sms.ui.theme.Cyan
import com.victorious.sms.ui.theme.Magenta
import com.victorious.sms.ui.theme.VictorSMSTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

data class CallLogEntry(val number: String, val name: String?, val type: Int, val dateMs: Long, val durationS: Long)

fun readCallLog(context: Context, limit: Int = 200): List<CallLogEntry> {
    if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return emptyList()
    val out = mutableListOf<CallLogEntry>()
    runCatching {
        context.contentResolver.query(CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
            while (c.moveToNext() && out.size < limit) out += CallLogEntry(c.getString(0).orEmpty(), c.getString(1), c.getInt(2), c.getLong(3), c.getLong(4))
        }
    }
    return out
}

@SuppressLint("MissingPermission")
fun placeCall(context: Context, number: String): Boolean {
    if (number.isBlank()) return false
    if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return false
    context.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
    return true
}

/** Dialer (keypad + call log). Handles DIAL/VIEW tel: so VictorSMS can hold ROLE_DIALER. */
class DialerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = intent?.data?.schemeSpecificPart.orEmpty()
        setContent { VictorSMSTheme { DialerScreen(initial) { finish() } } }
    }
}

@Composable
fun DialerScreen(initial: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var number by remember { mutableStateOf(initial) }
    var tab by remember { mutableIntStateOf(0) }
    var log by remember { mutableStateOf(emptyList<CallLogEntry>()) }
    var msg by remember { mutableStateOf<String?>(null) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    LaunchedEffect(tab) { if (tab == 1) log = withContext(Dispatchers.IO) { readCallLog(ctx) } }
    val onBg = MaterialTheme.colorScheme.onBackground
    Column(Modifier.fillMaxSize().background(Charcoal).safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Phone", color = Cyan, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back", color = Cyan) }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = ctx.getSystemService(RoleManager::class.java)
            if (!rm.isRoleHeld(RoleManager.ROLE_DIALER) && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                TextButton(onClick = {
                    role.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                    perms.launch(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS))
                }) { Text("Make VictorSMS your phone app", color = Magenta) }
            }
        }
        TabRow(selectedTabIndex = tab, containerColor = Charcoal) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Keypad") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Recents") })
        }
        if (tab == 0) {
            Text(number.ifEmpty { " " }, color = onBg, fontSize = 32.sp, modifier = Modifier.fillMaxWidth().padding(8.dp))
            Keypad { number += it }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = number.isNotBlank(), onClick = {
                    if (!placeCall(ctx, number)) { msg = "Allow the Phone permission first."; perms.launch(arrayOf(Manifest.permission.CALL_PHONE)) }
                }) { Text("Call") }
                TextButton(onClick = { number = number.dropLast(1) }) { Text("Delete", color = Cyan) }
            }
            msg?.let { Text(it, color = Magenta) }
        } else {
            if (log.isEmpty()) Text("No calls, or call log permission not granted.", color = onBg)
            LazyColumn {
                items(log) { e ->
                    Column(Modifier.fillMaxWidth().clickable { number = e.number; tab = 0 }.padding(vertical = 8.dp)) {
                        Text(e.name ?: e.number, color = onBg, fontWeight = FontWeight.SemiBold)
                        val kind = when (e.type) { CallLog.Calls.INCOMING_TYPE -> "incoming"; CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                            CallLog.Calls.MISSED_TYPE -> "missed"; CallLog.Calls.BLOCKED_TYPE -> "blocked"; CallLog.Calls.REJECTED_TYPE -> "rejected"; else -> "call" }
                        Text("$kind · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.dateMs))} · ${e.durationS}s",
                            color = Cyan.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun Keypad(onKey: (Char) -> Unit) {
    val rows = listOf("123", "456", "789", "*0#")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                r.forEach { ch -> Button(onClick = { onKey(ch) }, modifier = Modifier.weight(1f)) { Text(ch.toString(), fontSize = 22.sp) } }
            }
        }
    }
}
