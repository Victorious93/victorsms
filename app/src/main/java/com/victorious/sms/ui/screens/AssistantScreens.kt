package com.victorious.sms.ui.screens

import android.content.Intent
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.core.content.FileProvider
import com.victorious.sms.assist.ConsentedVoice
import com.victorious.sms.assist.Drafter
import com.victorious.sms.assist.PcSettings
import com.victorious.sms.assist.PcTts
import com.victorious.sms.assist.PersonaStore
import com.victorious.sms.assist.VoiceOwnership
import com.victorious.sms.assist.VoiceRegistry
import com.victorious.sms.ui.theme.Cyan
import com.victorious.sms.ui.theme.Magenta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun AssistantSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s = remember { PcSettings(ctx) }
    val persona = remember { PersonaStore(ctx) }
    val voices = remember { VoiceRegistry(ctx) }
    val scope = rememberCoroutineScope()
    val onBg = MaterialTheme.colorScheme.onBackground
    var llm by remember { mutableStateOf(s.llmUrl) }
    var model by remember { mutableStateOf(s.llmModel) }
    var tts by remember { mutableStateOf(s.ttsUrl) }
    var personaLen by remember { mutableStateOf(persona.text().length) }
    var info by remember { mutableStateOf<String?>(null) }
    var vlist by remember { mutableStateOf(voices.all()) }
    var vid by remember { mutableStateOf("") }
    var vname by remember { mutableStateOf("") }
    var own by remember { mutableStateOf(VoiceOwnership.OWN) }
    var from by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            info = runCatching { withContext(Dispatchers.IO) { persona.import(uri) } }.fold({ "Imported $it characters." }, { "Import failed: ${it.javaClass.simpleName}" })
            personaLen = persona.text().length
        }
    }
    ScreenColumn {
        Header("Assistant & persona", onBack)
        Text("PC endpoints on the Victorious Mesh. Empty = off. Text you draft is sent only to these URLs, and only when you tap a button.", color = onBg, fontSize = 12.sp)
        OutlinedTextField(llm, { llm = it; s.llmUrl = it }, label = { Text("LLM URL (Ollama-compatible)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model, { model = it; s.llmModel = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(tts, { tts = it; s.ttsUrl = it }, label = { Text("Voice URL (Chatterbox wrapper)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        var tok by remember { mutableStateOf(if (s.token.isEmpty()) "" else "••••••••") }
        OutlinedTextField(tok, { tok = it; if (!it.startsWith("•")) s.token = it }, label = { Text("PC token (from pc/setup.sh)") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())

        Text("Persona", color = Cyan, fontWeight = FontWeight.SemiBold)
        Text("$personaLen characters imported (persona.md, .txt, .json/.csv chat exports, or a .zip of them).", color = onBg, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(arrayOf("text/*", "application/zip", "application/json", "application/octet-stream")) }) { Text("Import") }
            TextButton(onClick = { persona.clear(); personaLen = 0 }) { Text("Clear", color = Magenta) }
        }
        info?.let { Text(it, color = Cyan, fontSize = 12.sp) }

        Text("Voices (consent gate)", color = Cyan, fontWeight = FontWeight.SemiBold)
        Text("Only your own voice, or the voice of someone who gave you explicit consent. Voice notes cannot use any other voice.", color = Magenta, fontSize = 12.sp)
        vlist.forEach { v ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${v.label} (${if (v.ownership == VoiceOwnership.OWN) "my voice" else "consent: ${v.consentFrom}"})", color = onBg, modifier = Modifier.weight(1f), fontSize = 13.sp)
                TextButton(onClick = { voices.remove(v.pcVoiceId); vlist = voices.all() }) { Text("Remove", color = Magenta) }
            }
        }
        OutlinedTextField(vid, { vid = it }, label = { Text("Voice id on the PC") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(vname, { vname = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = own == VoiceOwnership.OWN, onClick = { own = VoiceOwnership.OWN }); Text("This is my own voice", color = onBg, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = own == VoiceOwnership.CONSENTED, onClick = { own = VoiceOwnership.CONSENTED }); Text("Someone else, who consented", color = onBg, fontSize = 13.sp)
        }
        if (own == VoiceOwnership.CONSENTED) {
            OutlinedTextField(from, { from = it }, label = { Text("Who consented") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note, { note = it }, label = { Text("How and when (e.g. signed note, 2026-10-01)") }, modifier = Modifier.fillMaxWidth())
        }
        Button(onClick = {
            info = runCatching {
                voices.add(ConsentedVoice(vid.trim(), vname.trim(), own, from.trim(), note.trim(), System.currentTimeMillis()))
                vid = ""; vname = ""; from = ""; note = ""; vlist = voices.all(); "Voice added."
            }.getOrElse { it.message }
        }) { Text("I declare this, add voice") }
    }
}

/** Draft + voice-note controls for a thread. Never sends: the draft goes into the composer. */
@Composable
fun AssistBar(contact: String, history: () -> List<Pair<Boolean, String>>, draft: String, onDraft: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<File?>(null) }
    val voices = remember { VoiceRegistry(ctx).all() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { Drafter.draft(ctx, contact, history()) } }
                    .onSuccess { onDraft(it); info = "Draft ready. Review and edit before sending." }
                    .onFailure { info = "Draft failed: ${it.message ?: it.javaClass.simpleName}" }
                busy = false
            }
        }) { Text(if (busy) "Drafting…" else "Draft reply", color = Cyan, fontSize = 12.sp) }
        if (voices.isNotEmpty()) {
            TextButton(enabled = !busy && draft.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { PcTts.synthesize(ctx, PcSettings(ctx), voices.first(), draft) } }
                        .onSuccess { note = it; info = "Voice note made with \"${voices.first().label}\". Play it, then share it yourself if you want." }
                        .onFailure { info = "Voice note failed: ${it.message ?: it.javaClass.simpleName}" }
                    busy = false
                }
            }) { Text("Voice note", color = Cyan, fontSize = 12.sp) }
        }
        note?.let { f ->
            TextButton(onClick = { runCatching { MediaPlayer().apply { setDataSource(f.absolutePath); setOnCompletionListener { it.release() }; prepare(); start() } } }) {
                Text("Play", color = Cyan, fontSize = 12.sp)
            }
            TextButton(onClick = {
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/*").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share voice note"))
            }) { Text("Share", color = Cyan, fontSize = 12.sp) }
        }
    }
    info?.let { Text(it, color = Cyan, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
}
