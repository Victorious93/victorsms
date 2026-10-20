package com.victorious.sms.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.victorious.sms.assist.ChatMsg
import com.victorious.sms.assist.PcLlm
import com.victorious.sms.assist.PcSettings
import com.victorious.sms.fleet.DcaClient
import com.victorious.sms.fleet.NodeId
import com.victorious.sms.ui.theme.Charcoal
import com.victorious.sms.ui.theme.CharcoalSurface
import com.victorious.sms.ui.theme.Cyan
import com.victorious.sms.ui.theme.Magenta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class BotLine(val mine: Boolean, val text: String)

@Composable
fun SummarizerBotScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf(listOf(BotLine(false, "Paste or type messages to summarize or triage. I call your PC LLM (Settings → Assistant & persona). Nothing is sent automatically."))) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    BotChat("Summarizer / triage", lines, draft, { draft = it }, busy, onBack) {
        val text = draft; draft = ""; busy = true
        lines = lines + BotLine(true, text)
        scope.launch {
            val reply = runCatching {
                withContext(Dispatchers.IO) {
                    PcLlm.chat(PcSettings(ctx), listOf(
                        ChatMsg("system", "You are Don's summarizer and triage assistant. Be concise. Summarize, list action items, and flag anything urgent. Plain text only."),
                        ChatMsg("user", text),
                    ))
                }
            }.getOrElse { "Failed: ${it.message ?: it.javaClass.simpleName}" }
            lines = lines + BotLine(false, reply); busy = false
        }
    }
}

@Composable
fun FleetBotScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val nodeId = remember { NodeId.get(ctx) }
    var lines by remember {
        mutableStateOf(listOf(BotLine(false,
            "Fleet commands go to droidcommand-AI. Format: <actionRef> [json-args]. External control must be ON and droidcommand-AI approved in Connected apps. VictorSMS never executes anything. " +
                "Node id: $nodeId")))
    }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    BotChat("Fleet (droidcommand-AI)", lines, draft, { draft = it }, busy, onBack) {
        val text = draft.trim(); draft = ""; busy = true
        lines = lines + BotLine(true, text)
        scope.launch {
            val reply = withContext(Dispatchers.IO) {
                val client = DcaClient(ctx)
                client.gate()?.let { return@withContext it }
                val action = text.substringBefore(' ').trim()
                if (action.isBlank()) return@withContext "Type an actionRef, optionally followed by a JSON object of args."
                val args = runCatching { JSONObject(text.substringAfter(' ', "{}").ifBlank { "{}" }) }.getOrElse { return@withContext "Args must be a JSON object." }
                when (val r = client.call("remote.command", "controller", client.envelope("controller", action, args))) {
                    is DcaClient.Result.Blocked -> r.reason
                    is DcaClient.Result.Ok -> {
                        val j = r.response
                        if (!j.optBoolean("ok")) "DCA refused: " + (j.optJSONObject("error")?.optString("code") ?: j.optString("error"))
                        else "DCA accepted. Result: " + (j.opt("result")?.toString() ?: "ok") + "\n(DCA still decides and may ask you to confirm.)"
                    }
                }
            }
            lines = lines + BotLine(false, reply); busy = false
        }
    }
}

@Composable
private fun BotChat(title: String, lines: List<BotLine>, draft: String, onDraft: (String) -> Unit, busy: Boolean, onBack: () -> Unit, onSend: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Charcoal).safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Cyan, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back", color = Cyan) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), reverseLayout = true) {
            items(lines.asReversed()) { m ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
                    Text(m.text, color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.background(CharcoalSurface, RoundedCornerShape(12.dp)).padding(10.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(draft, onDraft, modifier = Modifier.weight(1f), placeholder = { Text(if (busy) "Working…" else "Message") })
            Button(enabled = !busy && draft.isNotBlank(), onClick = onSend) { Text("Send") }
        }
    }
}
