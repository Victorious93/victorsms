package com.victorious.sms.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.victorious.sms.sms.CarrierSms
import com.victorious.sms.sms.SmsSendReceipt
import com.victorious.sms.sms.SmsSim
import com.victorious.sms.sms.SmsSubmissionState
import com.victorious.sms.sms.SmsSubscriptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun SmsSimPicker(selected: Int?, onSelected: (Int?) -> Unit, enabled: Boolean = true) {
    val context = LocalContext.current
    val subscriptions = remember(context) { SmsSubscriptions(context) }
    var show by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf<List<SmsSim>?>(null) }
    LaunchedEffect(selected) { active = withContext(Dispatchers.IO) { subscriptions.active() } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        active = subscriptions.active()
        show = true
    }
    val currentLabel = active?.firstOrNull { it.id == selected }?.label
    val label = when {
        selected == null -> "System default SIM"
        currentLabel != null -> currentLabel
        active != null -> "Selected SIM inactive"
        else -> "Saved SIM"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(enabled = enabled, onClick = {
            if (subscriptions.canRead()) {
                active = subscriptions.active()
                show = true
            } else permission.launch(Manifest.permission.READ_PHONE_STATE)
        }) { Text("Choose SIM") }
    }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("SMS SIM") },
            text = {
                Column {
                    if (active == null) Text("Allow phone permission to list individual SIMs.")
                    if (active?.isEmpty() == true) Text("There are no active SIMs.")
                    TextButton(onClick = { onSelected(null); show = false }) {
                        Text(if (selected == null) "✓ System default" else "System default")
                    }
                    active.orEmpty().forEach { sim ->
                        TextButton(onClick = { onSelected(sim.id); show = false }) {
                            Text(if (selected == sim.id) "✓ ${sim.label}" else sim.label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { show = false }) { Text("Close") } },
        )
    }
}

@Composable
internal fun receiptClock(receipts: List<SmsSendReceipt>): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(receipts) {
        now = System.currentTimeMillis()
        val deadlines = receipts.filter { it.state(now) == SmsSubmissionState.SENDING }
            .map { it.createdAt + SmsSendReceipt.CALLBACK_WAIT_MS }.distinct().sorted()
        for (deadline in deadlines) {
            delay((deadline - System.currentTimeMillis()).coerceAtLeast(0))
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun CarrierSmsActivityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf<List<SmsSendReceipt>?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val timestamp = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    LaunchedEffect(context) {
        CarrierSms.revisions.collect {
            val result = withContext(Dispatchers.IO) { runCatching { CarrierSms.recent(context) } }
            rows = result.getOrNull()
            unavailable = result.isFailure
        }
    }
    val now = receiptClock(rows.orEmpty())
    Column(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("SMS activity", style = MaterialTheme.typography.titleLarge, color = colors.onBackground)
        }
        val shown = rows
        when {
            unavailable -> Text("SMS activity could not be loaded.", modifier = Modifier.padding(16.dp), color = colors.error)
            shown == null -> Text("Loading SMS activity…", modifier = Modifier.padding(16.dp), color = colors.onBackground)
            shown.isEmpty() -> Text("SMS you send will appear here with their status.",
                modifier = Modifier.padding(16.dp), color = colors.onBackground)
            else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(shown, key = { it.id }) { message ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(message.address, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                            Text(message.statusLabel(now), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                            Text(message.body, color = colors.onSurface)
                            Text("${message.sim.label} · ${timestamp.format(Date(message.createdAt))}",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
