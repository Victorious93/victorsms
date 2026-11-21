package com.victorious.sms.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victorious.sms.mail.Attachment
import com.victorious.sms.mail.MailAccount
import com.victorious.sms.mail.MailMessage
import com.victorious.sms.mail.MailRuntime
import com.victorious.sms.mail.MimeBuilder
import com.victorious.sms.mail.Outgoing
import com.victorious.sms.mail.OutboxState
import com.victorious.sms.mail.Security
import com.victorious.sms.mail.ServerConfig
import com.victorious.sms.mail.SafeHtml
import com.victorious.sms.ui.theme.Charcoal
import com.victorious.sms.ui.theme.CharcoalSurface
import com.victorious.sms.ui.theme.Cyan
import com.victorious.sms.ui.theme.Magenta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Manual server setup (works with any IMAP/SMTP provider). Passwords go to the Keystore-backed store only. */
@Composable
fun MailAccountsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val mail = remember { MailRuntime.get(ctx) }
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf(mail.accounts()) }
    var address by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }
    var imapHost by remember { mutableStateOf("") }; var imapPort by remember { mutableStateOf("993") }
    var smtpHost by remember { mutableStateOf("") }; var smtpPort by remember { mutableStateOf("465") }
    var smtpStartTls by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    Page("Email accounts", actions = { TextButton(onClick = onBack) { Text("Back", color = Cyan) } }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            accounts.forEach { a ->
                Row(Modifier.fillMaxWidth().background(CharcoalSurface, RoundedCornerShape(10.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.address, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
                        Text("IMAP ${a.imap.host}:${a.imap.port} · SMTP ${a.smtp.host}:${a.smtp.port}", color = Cyan.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                    TextButton(onClick = { mail.removeAccount(a.id); accounts = mail.accounts() }) { Text("Remove", color = Magenta) }
                }
            }
            Text("Add an account", color = Cyan, fontWeight = FontWeight.Bold)
            Text("Connections always use TLS (implicit TLS or required STARTTLS) and the server certificate is checked. Your password stays on this phone.",
                color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp)
            OutlinedTextField(address, { address = it; if (user.isBlank() || user == address.dropLast(1)) user = it }, label = { Text("Email address") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("Password or app password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row { OutlinedTextField(imapHost, { imapHost = it }, label = { Text("IMAP server") }, singleLine = true, modifier = Modifier.weight(1f)); Spacer(Modifier.padding(4.dp))
                OutlinedTextField(imapPort, { imapPort = it }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(0.4f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }
            Row { OutlinedTextField(smtpHost, { smtpHost = it }, label = { Text("SMTP server") }, singleLine = true, modifier = Modifier.weight(1f)); Spacer(Modifier.padding(4.dp))
                OutlinedTextField(smtpPort, { smtpPort = it }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(0.4f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(smtpStartTls, { smtpStartTls = it })
                Text("SMTP uses STARTTLS (port 587) instead of implicit TLS (port 465)", color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp)
            }
            status?.let { Text(it, color = Magenta, fontSize = 12.sp) }
            Button(enabled = address.contains('@') && pass.isNotEmpty() && imapHost.isNotBlank() && smtpHost.isNotBlank(), onClick = {
                val acct = runCatching {
                    MailAccount(java.util.UUID.randomUUID().toString(), address.trim(), name.trim(), user.trim().ifEmpty { address.trim() },
                        ServerConfig(imapHost.trim(), imapPort.toInt(), Security.SSL_TLS),
                        ServerConfig(smtpHost.trim(), smtpPort.toInt(), if (smtpStartTls) Security.STARTTLS else Security.SSL_TLS))
                }.getOrElse { status = "Check the server names and ports."; return@Button }
                status = "Testing the connection…"
                scope.launch {
                    val err = withContext(Dispatchers.IO) {
                        runCatching { mail.saveAccount(acct, pass); mail.imap(acct).listFolders(); null }.getOrElse { mail.removeAccount(acct.id); it.message ?: it.javaClass.simpleName }
                    }
                    if (err == null) { accounts = mail.accounts(); address = ""; pass = ""; status = "Account added." } else status = "Could not sign in: $err"
                }
            }) { Text("Test and add") }
        }
    }
}

/** One email conversation: sanitized text, remote images blocked, reply / reply-all / forward through the selected account. */
@Composable
fun MailThreadScreen(accountAddress: String, threadRoot: String, onCompose: (replyId: String, mode: String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val mail = remember { MailRuntime.get(ctx) }
    val scope = rememberCoroutineScope()
    val acct = remember(accountAddress) { mail.accounts().firstOrNull { it.address == accountAddress } }
    var msgs by remember { mutableStateOf(emptyList<MailMessage>()) }
    LaunchedEffect(threadRoot) {
        msgs = withContext(Dispatchers.IO) { acct?.let { a -> mail.cached(a).filter { it.threadRoot == threadRoot } }.orEmpty() }
        withContext(Dispatchers.IO) { runCatching { msgs.filter { !it.seen }.forEach { mail.markRead(acct!!, it) } } }
    }
    Page(msgs.lastOrNull()?.subject ?: "Conversation", actions = { TextButton(onClick = onBack) { Text("Back", color = Cyan) } }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            msgs.forEach { m ->
                Column(Modifier.fillMaxWidth().background(CharcoalSurface, RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Text(m.from, color = Magenta, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("To: " + m.to.joinToString(", "), color = Cyan.copy(alpha = 0.6f), fontSize = 11.sp)
                    // Untrusted content is shown as plain text derived from the sanitized HTML; no WebView, no remote loads.
                    val body = remember(m.messageId) {
                        m.htmlBody?.let { h -> SafeHtml.toPlainText(h) } ?: m.textBody
                    }
                    Text(body, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 6.dp))
                    if (m.htmlBody != null && SafeHtml.render(m.htmlBody!!).blockedRemoteImages > 0)
                        Text("Remote images are blocked.", color = Cyan.copy(alpha = 0.6f), fontSize = 11.sp)
                    Row {
                        TextButton(onClick = { onCompose(m.messageId, "reply") }) { Text("Reply", color = Cyan) }
                        TextButton(onClick = { onCompose(m.messageId, "all") }) { Text("Reply all", color = Cyan) }
                        TextButton(onClick = { onCompose(m.messageId, "fwd") }) { Text("Forward", color = Cyan) }
                    }
                }
            }
            if (acct == null) Text("This mailbox is no longer configured.", color = Magenta)
        }
    }
}

/** Compose / reply / forward. The From account is explicit; a failed send stays failed on that account. */
@Composable
fun MailComposeScreen(accountAddress: String, replyToId: String, mode: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val mail = remember { MailRuntime.get(ctx) }
    val scope = rememberCoroutineScope()
    val accounts = remember { mail.accounts() }
    var from by remember { mutableStateOf(accounts.firstOrNull { it.address == accountAddress } ?: accounts.firstOrNull()) }
    val original = remember { from?.let { a -> mail.cached(a).firstOrNull { it.messageId == replyToId } } }
    val seed = remember {
        val a = from
        if (a != null && original != null) when (mode) {
            "fwd" -> MimeBuilder.forward(original, a, emptyList(), "")
            "all" -> MimeBuilder.reply(original, a, "", all = true)
            else -> MimeBuilder.reply(original, a, "", all = false)
        } else null
    }
    var to by remember { mutableStateOf(seed?.to?.joinToString(", ").orEmpty()) }
    var cc by remember { mutableStateOf(seed?.cc?.joinToString(", ").orEmpty()) }
    var bcc by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf(seed?.subject.orEmpty()) }
    var body by remember { mutableStateOf(seed?.text.orEmpty()) }
    var atts by remember { mutableStateOf(emptyList<Attachment>()) }
    var status by remember { mutableStateOf<String?>(null) }
    val draftId = remember { java.util.UUID.randomUUID().toString() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null } ?: "attachment"
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
            atts = atts + Attachment(name, ctx.contentResolver.getType(uri) ?: "application/octet-stream", bytes)
        }
    }
    fun list(s: String) = s.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    fun build(): Outgoing? = from?.let { a ->
        (seed ?: Outgoing(a, emptyList(), subject = "", text = "")).copy(account = a, to = list(to), cc = list(cc), bcc = list(bcc), subject = subject, text = body, attachments = atts)
    }
    Page("Write email", actions = { TextButton(onClick = {
        build()?.let { runCatching { mail.drafts.save(draftId, it) } }; onDone()          // leaving saves a draft
    }) { Text("Save draft", color = Cyan) } }) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            if (accounts.isEmpty()) { Text("Add an email account first (Settings > Email accounts).", color = Magenta); return@Column }
            Text("From", color = Cyan, fontSize = 12.sp)
            Row { accounts.forEach { a -> TextButton(onClick = { from = a }) { Text(if (a == from) "[${a.address}]" else a.address, color = if (a == from) Magenta else Cyan, fontSize = 12.sp) } } }
            OutlinedTextField(to, { to = it }, label = { Text("To") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cc, { cc = it }, label = { Text("Cc") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(bcc, { bcc = it }, label = { Text("Bcc") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(subject, { subject = it }, label = { Text("Subject") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(body, { body = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), minLines = 6)
            atts.forEach { Text("📎 ${it.name} (${it.bytes.size / 1024} KB)", color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp) }
            status?.let { Text(it, color = Magenta, fontSize = 12.sp) }
            Row {
                OutlinedButton(onClick = { pick.launch("*/*") }) { Text("Attach", color = Cyan) }
                Spacer(Modifier.padding(6.dp))
                Button(enabled = list(to).isNotEmpty() && subject.isNotBlank(), onClick = {
                    val o = build() ?: return@Button
                    scope.launch {
                        // Persist to the outbox first; the send itself runs after, so a crash can never lose or double-send it.
                        val item = withContext(Dispatchers.IO) { mail.outbox.enqueue(o).also { mail.drafts.delete(draftId); mail.outbox.processQueued() } }
                        val st = withContext(Dispatchers.IO) { mail.outbox.get(item.id) }
                        status = when (st?.state) {
                            OutboxState.SENT -> null
                            OutboxState.FAILED -> "Not sent: ${st.reason}. It is in the Outbox."
                            OutboxState.UNCERTAIN -> "The connection broke while sending; it may have been delivered. Check the Outbox before resending."
                            else -> "Queued."
                        }
                        if (st?.state == OutboxState.SENT) onDone()
                    }
                }) { Text("Send") }
            }
        }
    }
}

/** Outbox: every queued, failed or uncertain message with the only safe actions for its state. */
@Composable
fun MailOutboxScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val mail = remember { MailRuntime.get(ctx) }
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(mail.outbox.list()) }
    fun refresh() { items = mail.outbox.list() }
    Page("Outbox", actions = { TextButton(onClick = onBack) { Text("Back", color = Cyan) } }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            if (items.isEmpty()) Text("Nothing waiting.", color = MaterialTheme.colorScheme.onBackground)
            items.forEach { i ->
                Column(Modifier.fillMaxWidth().background(CharcoalSurface, RoundedCornerShape(10.dp)).padding(12.dp)) {
                    Text(i.payload.subject.ifBlank { "(no subject)" }, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
                    Text("${i.state.name} · from ${i.payload.account.address} to ${i.payload.to.joinToString()}", color = Cyan.copy(alpha = 0.7f), fontSize = 12.sp)
                    if (i.reason.isNotBlank()) Text(i.reason, color = Magenta, fontSize = 12.sp)
                    Row {
                        when (i.state) {
                            OutboxState.QUEUED, OutboxState.FAILED -> TextButton(onClick = { scope.launch(Dispatchers.IO) { mail.outbox.retryFailed(i.id); mail.outbox.processQueued(); refresh() } }) { Text("Send now", color = Cyan) }
                            OutboxState.UNCERTAIN -> {
                                TextButton(onClick = { scope.launch(Dispatchers.IO) { mail.outbox.resolveUncertain(i.id) { a, id -> mail.imap(a).containsMessageId("Sent", id) || mail.imap(a).containsMessageId("INBOX", id) }; refresh() } }) { Text("Check Sent folder", color = Cyan) }
                                TextButton(onClick = { scope.launch(Dispatchers.IO) { mail.outbox.resendAsNew(i.id); mail.outbox.processQueued(); refresh() } }) { Text("Send again (may duplicate)", color = Magenta) }
                            }
                            else -> {}
                        }
                        TextButton(onClick = { mail.outbox.remove(i.id); refresh() }) { Text("Discard", color = Magenta) }
                    }
                }
            }
        }
    }
}
