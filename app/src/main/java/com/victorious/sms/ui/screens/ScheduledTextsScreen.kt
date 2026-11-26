package com.victorious.sms.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.victorious.comms.Providers
import com.victorious.comms.SchedState
import com.victorious.comms.ScheduledMessage
import com.victorious.sms.archive.ArchiveRuntime
import com.victorious.sms.sms.ScheduledSms
import com.victorious.sms.ui.theme.Charcoal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

private data class ScheduledRow(val message: ScheduledMessage, val title: String)

@Composable
fun ScheduledTextsScreen(conversation: Long?, onSaved: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    val limit = Providers.of("sms").maxBodyChars
    var rows by remember { mutableStateOf<List<ScheduledRow>?>(null) }
    var recipient by remember { mutableStateOf<String?>(null) }
    var body by rememberSaveable(conversation) { mutableStateOf("") }
    var sendAt by rememberSaveable(conversation) {
        mutableLongStateOf((System.currentTimeMillis() / 60_000L + 10) * 60_000L)
    }
    var confirm by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf<ScheduledMessage?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val time = Instant.ofEpochMilli(sendAt).atZone(zone)
    val timestamp = java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT,
    )

    LaunchedEffect(conversation) {
        ScheduledSms.revisions.collect {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val archive = ArchiveRuntime.get(context).archive
                    val messages = ScheduledSms.list(context, conversation)
                    val titles = messages.map { it.conversation }.distinct().associateWith { archive.conversation(it)?.title.orEmpty() }
                    conversation?.let(archive::smsRecipient) to messages.map { ScheduledRow(it, titles[it.conversation].orEmpty()) }
                }
                recipient = loaded.first
                rows = loaded.second
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = "Scheduled texts could not be loaded."
                rows = emptyList()
            }
        }
    }

    fun selectTime(local: LocalDateTime) {
        val offset = zone.rules.getValidOffsets(local).firstOrNull()
        if (offset == null) {
            error = "That time is skipped by a clock change. Choose another time."
            return
        }
        sendAt = local.toInstant(offset).toEpochMilli()
        error = null
    }

    fun save() {
        val cid = conversation ?: return
        val text = body
        val whenMs = sendAt
        busy = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { ScheduledSms.schedule(context, cid, text, whenMs) }
                body = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                error = e.message ?: "Check the text and time."
            } catch (_: Exception) {
                error = "The text could not be scheduled."
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Charcoal).safeDrawingPadding().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Scheduled texts", style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val to = recipient
                if (to != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("To $to · Phone SMS (default SIM)")
                        OutlinedTextField(
                            value = body,
                            onValueChange = { if (it.length <= limit) body = it },
                            label = { Text("Text message") },
                            minLines = 2,
                            maxLines = 5,
                            supportingText = { Text("${body.length} / $limit") },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(timestamp.format(Date(sendAt)))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !busy, onClick = {
                                DatePickerDialog(context, { _, year, month, day ->
                                    selectTime(LocalDateTime.of(year, month + 1, day, time.hour, time.minute))
                                }, time.year, time.monthValue - 1, time.dayOfMonth).show()
                            }) { Text("Date") }
                            OutlinedButton(enabled = !busy, onClick = {
                                TimePickerDialog(context, { _, hour, minute ->
                                    selectTime(time.toLocalDate().atTime(hour, minute))
                                }, time.hour, time.minute, DateFormat.is24HourFormat(context)).show()
                            }) { Text("Time") }
                        }
                        Text("Android may delay the alarm. Texts over 15 minutes late are marked Missed.",
                            style = MaterialTheme.typography.bodySmall)
                        Button(enabled = !busy && body.isNotBlank(), onClick = { confirm = true }) { Text("Schedule text") }
                    }
                } else if (rows != null) {
                    if (conversation != null) Text("This conversation has no single SMS recipient.")
                    OutlinedButton(onClick = onSaved) { Text("Open saved conversations") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            val list = rows
            when {
                list == null -> item { Text("Loading scheduled texts…") }
                list.isEmpty() -> item { Text("No scheduled texts yet.") }
                else -> items(list, key = { it.message.uid }) { row ->
                    val message = row.message
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        HorizontalDivider()
                        if (row.title.isNotBlank()) Text(row.title, style = MaterialTheme.typography.titleSmall)
                        Text("${timestamp.format(Date(message.sendAt))} · ${message.state.label()}")
                        Text(message.body)
                        if (message.detail.isNotBlank()) Text(message.detail, style = MaterialTheme.typography.bodySmall)
                        if (message.state == SchedState.PENDING) {
                            TextButton(enabled = !busy, onClick = { cancelling = message }) { Text("Cancel text") }
                        }
                    }
                }
            }
        }
    }

    if (confirm && recipient != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Schedule this text?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("To $recipient via phone SMS")
                    Text(timestamp.format(Date(sendAt)))
                    Text(body)
                }
            },
            confirmButton = { TextButton(onClick = { confirm = false; save() }) { Text("Schedule") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep editing") } },
        )
    }

    cancelling?.let { message ->
        AlertDialog(
            onDismissRequest = { cancelling = null },
            title = { Text("Cancel this scheduled text?") },
            confirmButton = {
                TextButton(onClick = {
                    cancelling = null
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            if (!withContext(Dispatchers.IO) { ScheduledSms.cancel(context, message.uid) }) {
                                error = "This text has already left the queue."
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            error = "The scheduled text could not be cancelled."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Cancel text") }
            },
            dismissButton = { TextButton(onClick = { cancelling = null }) { Text("Keep scheduled") } },
        )
    }
}

private fun SchedState.label(): String = when (this) {
    SchedState.PENDING -> "Scheduled"
    SchedState.SENDING -> "Submitting"
    SchedState.SENT -> "Submitted to Android"
    SchedState.FAILED -> "Submission failed"
    SchedState.UNKNOWN -> "Unknown outcome"
    SchedState.CANCELLED -> "Cancelled"
    SchedState.MISSED -> "Missed"
}
