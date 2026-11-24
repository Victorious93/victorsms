package com.victorious.sms.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.victorious.comms.ColumnMapping
import com.victorious.comms.ConversationSummary
import com.victorious.comms.DateOrder
import com.victorious.comms.ImportPreview
import com.victorious.comms.ImportRecord
import com.victorious.comms.ImportResult
import com.victorious.comms.Resolution
import com.victorious.comms.Row
import com.victorious.comms.StoredMessage
import com.victorious.sms.archive.ArchiveRuntime
import com.victorious.sms.archive.ImportSession
import com.victorious.sms.archive.ImportSource
import com.victorious.sms.archive.PendingShare
import com.victorious.sms.ui.theme.Charcoal
import com.victorious.sms.ui.theme.Cyan
import com.victorious.sms.ui.theme.Magenta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

// Victorious Framework: Import Center. Thin screens over ImportSession / comms-core (both unit-tested). Not run on a device.

private fun stamp(ts: Long?): String = ts?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "unknown"

@Composable
private fun ImportHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("‹ Back", color = Cyan) }
        Text(title, color = Cyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ImportNote(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onBackground) {
    Text(text, color = color, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
}

// ---------------------------------------------------------------------------------------------------------------------

@Composable
fun SavedConversationsScreen(onOpen: (Long) -> Unit, onImport: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<ConversationSummary>?>(null) }
    LaunchedEffect(Unit) { items = withContext(Dispatchers.IO) { ArchiveRuntime.get(ctx).archive.conversations() } }
    Column(Modifier.fillMaxSize().background(Charcoal).safeDrawingPadding().padding(16.dp)) {
        ImportHeader("Saved conversations", onBack)
        ImportNote("Conversations you import are saved on this phone. Imported history never causes automatic replies.")
        Button(onClick = onImport, modifier = Modifier.padding(vertical = 8.dp)) { Text("Import a conversation") }
        val list = items
        when {
            list == null -> ImportNote("Loading…")
            list.isEmpty() -> ImportNote("Nothing saved yet. Export a conversation from TextNow, WhatsApp or an SMS backup app, then import it here.")
            else -> LazyColumn {
                items(list, key = { it.id }) { c ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(c.id) }.padding(vertical = 8.dp)) {
                        Text(c.title.ifBlank { "Conversation" }, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                        ImportNote("${c.provider} · ${c.messageCount} messages · last ${stamp(c.lastTs)}${if (c.assistantAccess) " · shared with assistant" else ""}", Magenta)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------

@Composable
fun ImportWizardScreen(targetConversation: Long?, onOpenConversation: (Long) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val handle = remember { ArchiveRuntime.get(ctx) }
    val session = remember { ImportSession(handle.engine).also { it.targetConversation = targetConversation } }
    var rev by remember { mutableIntStateOf(0) }
    var fileLabel by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var result by remember { mutableStateOf<ImportResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val targetTitle = remember(targetConversation) { targetConversation?.let { handle.archive.conversation(it)?.title } }

    fun touch() { rev++ }

    fun load(uri: Uri) {
        scope.launch {
            busy = true; error = null; result = null
            runCatching { withContext(Dispatchers.IO) { ImportSource.fromUri(ctx.contentResolver, uri) } }
                .onSuccess { session.input = it; fileLabel = "${it.name} (${it.bytes.size / 1024} KB)"; touch() }
                .onFailure { error = it.message ?: "That file could not be read."; busy = false }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) load(uri) }

    // A file shared in from another app (WhatsApp "Export chat", a file manager) is picked up once.
    LaunchedEffect(Unit) { PendingShare.take().first?.let(::load) }

    LaunchedEffect(rev) {
        if (session.input != null) {
            busy = true
            val r = runCatching { withContext(Dispatchers.Default) { session.preview() } }
            preview = r.getOrNull()
            error = r.exceptionOrNull()?.let { "Could not read this file: ${it.message}" }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().background(Charcoal).safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState())) {
        ImportHeader(if (targetConversation != null) "Update conversation" else "Import a conversation", onBack)
        if (targetTitle != null) ImportNote("Adding newer messages to “$targetTitle”. Messages already saved are recognised and kept; nothing is deleted.", Magenta)
        ImportNote("Supported: CSV, JSON, SMS Backup & Restore XML, WhatsApp “Export chat” text, pasted text with dates, and ZIPs of those. HTML is not supported. TextNow exports work as CSV, JSON or text if they match; there is no dedicated TextNow reader yet.")
        Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.padding(vertical = 8.dp)) { Text(if (fileLabel == null) "Choose a file" else "Choose a different file") }
        fileLabel?.let { ImportNote("Selected: $it") }
        error?.let { ImportNote(it, MaterialTheme.colorScheme.error) }
        if (busy) ImportNote("Reading…")

        val p = preview
        if (session.input != null && p != null) {
            OutlinedTextField(session.providerLabel, { session.providerLabel = it; touch() }, label = { Text("Provider (for example TextNow)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(session.account, { session.account = it; touch() }, label = { Text("Your number or username there (optional; helps match later exports)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dates:", color = MaterialTheme.colorScheme.onBackground)
                listOf<Pair<String, DateOrder?>>("Auto" to null, "Month first" to DateOrder.MDY, "Day first" to DateOrder.DMY).forEach { (label, v) ->
                    TextButton(onClick = { session.dateOrder = v; touch() }) { Text(label, color = if (session.dateOrder == v) Cyan else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)) }
                }
            }
            if (p.needsMapping.isNotEmpty()) {
                ImportNote("I could not find the ${p.needsMapping.joinToString(" and ")} column. Type the column names exactly as in the file’s first row.", Magenta)
                val m = session.mapping
                OutlinedTextField(m.timestamp.orEmpty(), { session.mapping = session.mapping.copy(timestamp = it.ifBlank { null }); touch() }, label = { Text("Date/time column") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(m.body.orEmpty(), { session.mapping = session.mapping.copy(body = it.ifBlank { null }); touch() }, label = { Text("Message text column") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(m.sender.orEmpty(), { session.mapping = session.mapping.copy(sender = it.ifBlank { null }); touch() }, label = { Text("Sender column (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(m.direction.orEmpty(), { session.mapping = session.mapping.copy(direction = it.ifBlank { null }); touch() }, label = { Text("Sent/received column (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if (p.unresolvedSenders.isNotEmpty()) {
                ImportNote("Which of these is you? The file does not say which messages you sent. I will not guess.", Magenta)
                (p.unresolvedSenders + session.selfSenders).distinct().forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { if (s in session.selfSenders) session.selfSenders.remove(s) else session.selfSenders.add(s); touch() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = s in session.selfSenders, onCheckedChange = null)
                        Text(s, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp))
                    }
                }
            }

            // ---- where it goes ----
            if (targetConversation == null && p.groups.size == 1) {
                val g = p.groups[0]
                val selected = when { session.createNew -> "new"; session.targetConversation != null -> "c${session.targetConversation}"; else -> "auto" }
                ImportNote(if (g.target != null) "This looks like a conversation you already saved." else "This will be saved as a new conversation.", Cyan)
                if (g.target != null) {
                    Row(Modifier.fillMaxWidth().clickable { session.createNew = false; session.targetConversation = null; touch() }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == "auto", onClick = null)
                        Text("Add to the saved conversation", color = MaterialTheme.colorScheme.onBackground)
                    }
                }
                g.candidates.forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable { session.createNew = false; session.targetConversation = c.conversation; touch() }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == "c${c.conversation}", onClick = null)
                        Text("Add to “${c.title}” (${c.reason})", color = MaterialTheme.colorScheme.onBackground)
                    }
                }
                if (g.target != null || g.candidates.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().clickable { session.createNew = true; session.targetConversation = null; touch() }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == "new", onClick = null)
                        Text("Save as a separate conversation", color = MaterialTheme.colorScheme.onBackground)
                    }
                }
            } else if (p.groups.size > 1) {
                ImportNote("This file contains ${p.groups.size} conversations. Each is matched to a saved one by contact, or saved as new.", Cyan)
            }

            // ---- preview ----
            p.groups.forEach { g ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(g.title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                    ImportNote("People: ${g.participants.joinToString()}")
                    ImportNote("${g.parsed} messages read · ${g.outgoing} sent by you · ${g.incoming} received")
                    ImportNote("${stamp(g.firstTs)} → ${stamp(g.lastTs)}")
                    ImportNote("New: ${g.newMessages} · already saved: ${g.matched} (close matches: ${g.nearDuplicates}) · conflicts: ${g.conflicts}", Magenta)
                }
            }
            if (p.errors.isNotEmpty()) {
                ImportNote("${p.errors.size} problems were found; those lines are skipped, the rest is imported:", MaterialTheme.colorScheme.error)
                p.errors.take(5).forEach { ImportNote("• line ${it.line}: ${it.message}") }
            }
            p.warnings.forEach { ImportNote("⚠ $it", Magenta) }
            val shift = session.suggestedShift
            if (shift != null) {
                Row(Modifier.fillMaxWidth().clickable { session.applyShift = !session.applyShift; touch() }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = session.applyShift, onCheckedChange = null)
                    Text("Shift the times in this file by ${"%.2f".format(shift / 3_600_000.0)} h so they line up with what is saved", color = MaterialTheme.colorScheme.onBackground)
                }
            }
            if (p.alreadyImportedAs.isNotEmpty()) {
                ImportNote("This exact file was already imported. Importing it again will not create duplicates, but it will add nothing.", Magenta)
                Row(Modifier.fillMaxWidth().clickable { session.force = !session.force; touch() }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = session.force, onCheckedChange = null)
                    Text("Import it again anyway", color = MaterialTheme.colorScheme.onBackground)
                }
            }
            p.blockedReason?.let { ImportNote(it, MaterialTheme.colorScheme.error) }
            val canSave = p.canCommit && !busy && result == null && (p.alreadyImportedAs.isEmpty() || session.force)
            Button(
                enabled = canSave,
                onClick = {
                    scope.launch {
                        busy = true
                        val r = runCatching { withContext(Dispatchers.IO) { session.commit() } }
                        result = r.getOrNull()
                        error = r.exceptionOrNull()?.message
                        busy = false
                    }
                },
                modifier = Modifier.padding(vertical = 8.dp),
            ) { Text(if (p.groups.firstOrNull()?.target != null || targetConversation != null) "Add the new messages" else "Save conversation") }
            ImportNote("The original file is kept untouched, and every import can be undone from the conversation.")
        }

        result?.let { r ->
            if (r.skippedReason != null) ImportNote(r.skippedReason!!, Magenta)
            r.groups.forEach { g ->
                ImportNote(
                    (if (g.createdConversation) "Saved a new conversation: " else "Updated: ") + "${g.added} added, ${g.matched} already saved" + if (g.conflicts > 0) ", ${g.conflicts} conflicts to review" else "",
                    Cyan,
                )
            }
            r.groups.firstOrNull()?.let { g -> Button(onClick = { onOpenConversation(g.conversation) }) { Text("Open the conversation") } }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------

private class Detail(
    val conv: ConversationSummary?, val recent: List<StoredMessage>, val history: List<ImportRecord>,
    val conflicts: List<Pair<Row, StoredMessage?>>,
    /** Newest requests from paired assistant apps about this conversation: who and when, never what was read. */
    val access: List<Row> = emptyList(),
)

@Composable
fun SavedConversationScreen(id: Long, onUpdate: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val h = remember { ArchiveRuntime.get(ctx) }
    var rev by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<Detail?>(null) }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<StoredMessage>?>(null) }
    var undo by remember { mutableStateOf<ImportRecord?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var delFacts by remember { mutableStateOf(true) }
    var delSummaries by remember { mutableStateOf(true) }
    var delOriginals by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(rev) {
        detail = withContext(Dispatchers.IO) {
            val conv = h.archive.conversation(id)
            Detail(
                conv, h.archive.messages(id, 40), h.engine.history(id),
                h.archive.openConflicts(id).map { it to it["message"]?.toLongOrNull()?.let(h.archive::message) },
                conv?.let { c -> h.archive.recentAccess(300).filter { it["conversation"] == c.uid }.take(5) }.orEmpty(),
            )
        }
    }
    LaunchedEffect(query, rev) {
        found = if (query.isBlank()) null else withContext(Dispatchers.IO) { h.archive.search(query, id, 30, matchAll = true) }
    }

    val d = detail
    Column(Modifier.fillMaxSize().background(Charcoal).safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState())) {
        ImportHeader(d?.conv?.title?.ifBlank { "Conversation" } ?: "Conversation", onBack)
        if (d == null) { ImportNote("Loading…"); return@Column }
        val c = d.conv
        if (c == null) { ImportNote("This conversation no longer exists."); return@Column }
        ImportNote("${c.provider}${if (c.account.isNotBlank()) " · " + c.account else ""} · ${c.messageCount} messages · last ${stamp(c.lastTs)}", Magenta)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onUpdate) { Text("Update conversation") }
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete…") }
        }
        note?.let { ImportNote(it, Cyan) }

        ImportNote("Assistant access", Cyan)
        Row(
            Modifier.fillMaxWidth().clickable {
                scope.launch { withContext(Dispatchers.IO) { h.archive.setAssistantAccess(id, !c.assistantAccess) }; rev++ }
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(c.assistantAccess, null)
            Text(
                "Let my assistant (droidcommand-AI) read this conversation",
                color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            )
        }
        ImportNote(
            if (c.assistantAccess) "On. An app can read this only if you approved it and granted it \"Archive read\" under Connected apps. Switch it off to stop at once."
            else "Off. No app can see that this conversation exists. Turning it on never sends anything anywhere by itself.",
        )
        d.access.forEach { r ->
            ImportNote("${r["caller"].orEmpty()} · ${r["op"].orEmpty().substringAfterLast('.')} · ${stamp(r["ts"]?.toLongOrNull())} · ${r["outcome"].orEmpty()}${r["returned"]?.takeIf { it != "0" }?.let { " · $it items" } ?: ""}", Magenta)
        }

        OutlinedTextField(query, { query = it }, label = { Text("Search this conversation") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        val shown = found ?: d.recent
        ImportNote(if (found != null) "${shown.size} matches (every word must appear)" else "Most recent messages", Cyan)
        shown.forEach { m ->
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                ImportNote("${if (m.direction == com.victorious.comms.Direction.OUT) "You" else m.sender.ifBlank { "Them" }} · ${stamp(m.ts)}${if (m.origin == "live") "" else " · imported"}", Magenta)
                Text(m.body, color = MaterialTheme.colorScheme.onBackground)
            }
        }

        if (d.conflicts.isNotEmpty()) {
            ImportNote("Conflicts: the same message appears with different text in a newer file. Nothing was overwritten.", MaterialTheme.colorScheme.error)
            d.conflicts.forEach { (row, existing) ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    ImportNote("Saved: ${existing?.body ?: "(missing)"}")
                    ImportNote("New file: ${row["incoming_body"].orEmpty()}")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val cid = row["id"]?.toLongOrNull() ?: return@Row
                        listOf("Keep saved" to Resolution.KEEP_EXISTING, "Use new" to Resolution.TAKE_INCOMING, "Keep both" to Resolution.KEEP_BOTH).forEach { (label, res) ->
                            OutlinedButton(onClick = { scope.launch { withContext(Dispatchers.IO) { h.archive.resolveConflict(cid, res) }; rev++ } }) { Text(label, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }

        ImportNote("Imports", Cyan)
        d.history.forEach { r ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                ImportNote("${r.fileName.ifBlank { "import" }} · ${stamp(r.created)} · ${r.status.replace('_', ' ')}")
                ImportNote("${r.added} added · ${r.matched} already saved · ${r.conflicts} conflicts · ${r.totalAfter} messages afterwards", Magenta)
                if (r.status == "committed") TextButton(onClick = { undo = r }) { Text("Undo this import", color = Cyan) }
            }
        }
    }

    undo?.let { r ->
        AlertDialog(
            onDismissRequest = { undo = null },
            title = { Text("Undo this import?") },
            text = { Text("Removes the ${r.added} messages only this import added. Messages that other imports also contained stay. This cannot be redone, but you can import the file again.") },
            confirmButton = {
                TextButton(onClick = {
                    undo = null
                    scope.launch {
                        val res = runCatching { withContext(Dispatchers.IO) { h.engine.rollback(r.id) } }
                        note = if (res.isSuccess) "Import undone." else "Could not undo: ${res.exceptionOrNull()?.message}"
                        rev++
                        if (h.archive.conversation(id) == null) onBack()
                    }
                }) { Text("Undo import") }
            },
            dismissButton = { TextButton(onClick = { undo = null }) { Text("Cancel") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this conversation?") },
            text = {
                Column {
                    Text("Deletes every saved message and its search index from this phone. This cannot be undone. Choose what else goes with it:")
                    Row(Modifier.fillMaxWidth().clickable { delFacts = !delFacts }, verticalAlignment = Alignment.CenterVertically) { Checkbox(delFacts, null); Text("Facts I approved about it", modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp)) }
                    Row(Modifier.fillMaxWidth().clickable { delSummaries = !delSummaries }, verticalAlignment = Alignment.CenterVertically) { Checkbox(delSummaries, null); Text("Summaries", modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp)) }
                    Row(Modifier.fillMaxWidth().clickable { delOriginals = !delOriginals }, verticalAlignment = Alignment.CenterVertically) { Checkbox(delOriginals, null); Text("The original export files", modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp)) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { withContext(Dispatchers.IO) { h.archive.deleteConversation(id, delFacts, delSummaries, delOriginals) }; onBack() }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
