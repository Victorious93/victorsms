package com.victorious.sms.ui.screens

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.victorious.mail.*
import com.victorious.sms.mail.MailRepository
import com.victorious.sms.mail.OAuthRegistry
import com.victorious.sms.mail.StoredAccount
import com.victorious.sms.ui.theme.CharcoalSurface
import com.victorious.sms.ui.theme.Cyan
import com.victorious.sms.ui.theme.Magenta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Email accounts: manual IMAP/SMTP setup (TLS required), test connection, remove. OAuth providers appear only when this project has a client registration for them. */
@Composable
fun MailAccountsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current; val repo = remember { MailRepository.get(ctx) }; val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf(repo.storedAccounts()) }
    var adding by remember { mutableStateOf(accounts.isEmpty()) }
    Page("Email accounts", actions = { TextButton(onClick = onBack) { Text("Back", color = Cyan) } }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            accounts.forEach { a ->
                Row(Modifier.fillMaxWidth().background(CharcoalSurface, RoundedCornerShape(8.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(a.displayName, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold); Text("${a.address} · ${a.imapHost}", color = Cyan, fontSize = 12.sp) }
                    TextButton(onClick = { repo.removeAccount(a.id); accounts = repo.storedAccounts() }) { Text("Remove", color = Magenta) }
                }
            }
            if (!adding) TextButton(onClick = { adding = true }) { Text("Add account", color = Cyan) }
            else {
                var addr by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }; var user by remember { mutableStateOf("") }; var pw by remember { mutableStateOf("") }
                var ih by remember { mutableStateOf("") }; var ip by remember { mutableStateOf("993") }; var isTls by remember { mutableStateOf(true) }
                var sh by remember { mutableStateOf("") }; var sp by remember { mutableStateOf("465") }; var sTls by remember { mutableStateOf(true) }
                var pin by remember { mutableStateOf("") }; var msg by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
                Text("Add an email account (manual setup)", color = Cyan, fontWeight = FontWeight.Bold)
                Text("Servers must use TLS (implicit TLS or STARTTLS); plaintext is not offered. Certificates are validated; for a private server you can pin its certificate fingerprint below.", color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp)
                if (OAuthRegistry.configuredProviders.isEmpty()) Text("Sign in with Google/Microsoft is not available in this build: it needs this project's own OAuth client registration per provider. Use an app password with manual setup.", color = Magenta, fontSize = 12.sp)
                OutlinedTextField(addr, { addr = it; if (user.isEmpty() || user == addr.dropLast(1)) user = it }, label = { Text("Email address") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pw, { pw = it }, label = { Text("Password / app password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Row { OutlinedTextField(ih, { ih = it }, label = { Text("IMAP host") }, singleLine = true, modifier = Modifier.weight(2f)); OutlinedTextField(ip, { ip = it }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f)) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(isTls, { isTls = it; ip = if (it) "993" else "143" }); Text(if (isTls) "IMAP: implicit TLS" else "IMAP: STARTTLS (required)", color = MaterialTheme.colorScheme.onBackground) }
                Row { OutlinedTextField(sh, { sh = it }, label = { Text("SMTP host") }, singleLine = true, modifier = Modifier.weight(2f)); OutlinedTextField(sp, { sp = it }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f)) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(sTls, { sTls = it; sp = if (it) "465" else "587" }); Text(if (sTls) "SMTP: implicit TLS" else "SMTP: STARTTLS (required)", color = MaterialTheme.colorScheme.onBackground) }
                OutlinedTextField(pin, { pin = it }, label = { Text("Pin server certificate SHA-256 (optional, private servers)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                msg?.let { Text(it, color = if (it.startsWith("Connected")) Cyan else Magenta) }
                Button(enabled = !busy && addr.contains('@') && ih.isNotBlank() && sh.isNotBlank() && pw.isNotEmpty(), onClick = {
                    busy = true; msg = null
                    val acct = StoredAccount(UUID.randomUUID().toString(), addr.trim(), name.ifBlank { addr.substringBefore('@') }, user.ifBlank { addr }.trim(), ih.trim(), ip.toIntOrNull() ?: 993, if (isTls) Security.SSL_TLS else Security.STARTTLS,
                        sh.trim(), sp.toIntOrNull() ?: 465, if (sTls) Security.SSL_TLS else Security.STARTTLS, null, pin.trim().ifBlank { null }, pin.trim().ifBlank { null })
                    scope.launch {
                        val err = withContext(Dispatchers.IO) { repo.test(acct, pw) }
                        if (err == null) { withContext(Dispatchers.IO) { repo.addAccount(acct, pw); repo.syncAll() }; accounts = repo.storedAccounts(); adding = false; pw = ""; msg = null } else msg = "Could not connect: $err"
                        busy = false
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Checking…" else "Test and add") }
            }
        }
    }
}

/** One email conversation (thread): messages newest last, with Reply / Reply all / Forward that always use THIS account. */
@Composable
fun MailThreadScreen(accountId: String, threadKey: String, onReply: (mode: String, ref: String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current; val repo = remember { MailRepository.get(ctx) }; val scope = rememberCoroutineScope()
    var msgs by remember { mutableStateOf(repo.db.messages(accountId, threadKey)) }
    var remote by remember { mutableStateOf(setOf<Long>()) }
    val acct = remember { repo.storedAccounts().firstOrNull { it.id == accountId } }
    LaunchedEffect(Unit) { msgs.filter { !it.seen }.forEach { m -> repo.db.markSeen(m.accountId, m.folder, m.uid); scope.launch(Dispatchers.IO) { runCatching { repo.account(accountId)?.let { ImapClient(it).setSeen(m.folder, m.uid, true) } } } } }
    Page(msgs.firstOrNull()?.subject ?: "Email", actions = { TextButton(onClick = onBack) { Text("Back", color = Cyan) } }) {
        Column(Modifier.fillMaxSize()) {
            Text("Account: ${acct?.address ?: accountId} (replies go out from this account)", color = Cyan, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(msgs, key = { "${it.folder}/${it.uid}" }) { m ->
                    Column(Modifier.fillMaxWidth().padding(10.dp).background(CharcoalSurface, RoundedCornerShape(10.dp)).padding(10.dp)) {
                        Text(m.from.joinToString(), color = Magenta, fontSize = 12.sp)
                        Text("To: ${m.to.joinToString()}" + if (m.cc.isNotEmpty()) "  Cc: ${m.cc.joinToString()}" else "", color = Cyan.copy(alpha = .7f), fontSize = 11.sp)
                        val html = m.html
                        if (html != null && m.text.isNullOrBlank()) {
                            val r = remember(m.uid, m.uid in remote) { HtmlSanitizer.sanitize(html, allowRemoteImages = m.uid in remote) }
                            AndroidView(factory = { c -> WebView(c).apply { settings.javaScriptEnabled = false; settings.allowFileAccess = false; settings.allowContentAccess = false; settings.blockNetworkLoads = (m.uid !in remote); setBackgroundColor(0); webViewClient = object : WebViewClient() { override fun shouldOverrideUrlLoading(v: WebView, req: android.webkit.WebResourceRequest): Boolean { runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, req.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; return true } } } },
                                update = { it.loadDataWithBaseURL(null, "<html><body style='color:#ddd;font-family:sans-serif'>${r.html}</body></html>", "text/html", "utf-8", null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 400.dp))
                            if (r.blockedRemoteImages > 0) TextButton(onClick = { remote = remote + m.uid }) { Text("${r.blockedRemoteImages} remote image(s) blocked — show for this message", color = Cyan, fontSize = 12.sp) }
                        } else Text(m.text.orEmpty(), color = MaterialTheme.colorScheme.onBackground)
                        if (m.attachments.isNotEmpty()) m.attachments.forEach { a -> Text("📎 ${a.filename} (${a.bytes.size} B)", color = Cyan, fontSize = 12.sp) }
                        Row { listOf("reply" to "Reply", "replyall" to "Reply all", "forward" to "Forward").forEach { (k, l) -> TextButton(onClick = { onReply(k, "${m.folder}/${m.uid}") }) { Text(l, color = Cyan) } } }
                    }
                }
            }
        }
    }
}

/** Compose / reply / reply-all / forward. The From identity is the selected account; Send queues in the durable outbox and shows the real state. */
@Composable
fun MailComposeScreen(accountId: String, mode: String, ref: String, onDone: () -> Unit) {
    val ctx = LocalContext.current; val repo = remember { MailRepository.get(ctx) }; val scope = rememberCoroutineScope()
    val stored = remember { repo.storedAccounts() }
    var acctId by remember { mutableStateOf(accountId.ifBlank { stored.firstOrNull()?.id.orEmpty() }) }
    val parent = remember(ref) { ref.split("/", limit = 2).takeIf { it.size == 2 }?.let { p -> repo.db.messages(accountId).firstOrNull { it.folder == p[0] && it.uid == p[1].toLongOrNull() } } }
    val seed = remember(parent, mode) { val a = repo.account(acctId); if (parent != null && a != null) when (mode) { "reply" -> Compose.reply(parent, a, "", false, ""); "replyall" -> Compose.reply(parent, a, "", true, ""); "forward" -> Compose.forward(parent, a, emptyList(), "", ""); else -> null } else null }
    var to by remember { mutableStateOf(seed?.to?.joinToString { it.address }.orEmpty()) }; var cc by remember { mutableStateOf(seed?.cc?.joinToString { it.address }.orEmpty()) }; var bcc by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf(seed?.subject.orEmpty()) }; var body by remember { mutableStateOf(seed?.body.orEmpty()) }
    var atts by remember { mutableStateOf(seed?.attachments.orEmpty()) }; var status by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let { u ->
        val name = ctx.contentResolver.query(u, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow("_display_name")) else null } ?: "attachment"
        val bytes = ctx.contentResolver.openInputStream(u)?.use { it.readBytes() }; if (bytes != null) atts = atts + AttachmentData(name, ctx.contentResolver.getType(u) ?: "application/octet-stream", bytes) } }
    fun addrs(s: String) = s.split(',', ';', ' ').map { it.trim() }.filter { it.contains('@') }.map { Addr(it) }
    fun build(): Draft = Draft(UUID.randomUUID().toString(), acctId, addrs(to), addrs(cc), addrs(bcc), subject.replace('\n', ' '), body, atts, seed?.inReplyTo, seed?.references.orEmpty())
    Page("Compose", actions = { TextButton(onClick = onDone) { Text("Cancel", color = Cyan) } }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("From:", color = Cyan, fontSize = 12.sp)
            stored.forEach { a -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { acctId = a.id }) { RadioButton(acctId == a.id, { acctId = a.id }); Text("${a.displayName} <${a.address}>", color = MaterialTheme.colorScheme.onBackground) } }
            if (stored.isEmpty()) Text("Add an email account first (Settings → Email accounts).", color = Magenta)
            OutlinedTextField(to, { to = it }, label = { Text("To") }, modifier = Modifier.fillMaxWidth()); OutlinedTextField(cc, { cc = it }, label = { Text("Cc") }, modifier = Modifier.fillMaxWidth()); OutlinedTextField(bcc, { bcc = it }, label = { Text("Bcc") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(subject, { subject = it }, label = { Text("Subject") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(body, { body = it }, label = { Text("Message") }, minLines = 6, modifier = Modifier.fillMaxWidth())
            atts.forEach { Text("📎 ${it.filename} (${it.bytes.size} B)", color = Cyan, fontSize = 12.sp) }
            Row { TextButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text("Attach file", color = Cyan) }
                TextButton(enabled = acctId.isNotBlank(), onClick = { scope.launch(Dispatchers.IO) { runCatching { repo.db.saveDraft(build()) }; status = "Draft saved on this phone" } }) { Text("Save draft", color = Cyan) } }
            status?.let { Text(it, color = Cyan) }
            Button(enabled = acctId.isNotBlank() && addrs(to + "," + cc + "," + bcc).isNotEmpty(), onClick = {
                val d = build(); status = "Sending…"
                scope.launch(Dispatchers.IO) {
                    // enqueue() refuses an account whose password is missing; that must be a message, not a crash.
                    val item = runCatching { repo.outbox.enqueue(d) }.getOrElse { status = "Not sent: this account has no saved password. Re-add it in Settings → Email accounts."; return@launch }
                    runCatching { repo.flushOutbox() }; val s = repo.db.SqlOutboxStore().get(item.draft.id)
                    status = when (s?.state) { OutState.SENT -> "Sent"; OutState.QUEUED -> "Queued — will be sent when the connection is back (see Outbox)"; OutState.UNCERTAIN -> "Connection lost while sending — it may have been delivered. See Outbox before sending again."; else -> "Not sent: ${s?.detail}" }
                    if (s?.state == OutState.SENT) withContext(Dispatchers.Main) { onDone() }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Send") }
        }
    }
}

/** Outbox with honest states. UNCERTAIN items are never re-sent automatically: the owner chooses. */
@Composable
fun MailOutboxScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current; val repo = remember { MailRepository.get(ctx) }; val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(repo.db.SqlOutboxStore().all().filter { it.state != OutState.SENT }) }
    fun refresh() { items = repo.db.SqlOutboxStore().all().filter { it.state != OutState.SENT } }
    Page("Outbox", actions = { TextButton(onClick = onBack) { Text("Back", color = Cyan) } }) {
        LazyColumn(Modifier.fillMaxSize()) {
            if (items.isEmpty()) item { Text("Nothing waiting.", color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(24.dp)) }
            items(items, key = { it.draft.id }) { it ->
                Column(Modifier.fillMaxWidth().padding(12.dp).background(CharcoalSurface, RoundedCornerShape(8.dp)).padding(10.dp)) {
                    Text("${it.draft.subject.ifBlank { "(no subject)" }} → ${it.draft.to.joinToString { a -> a.address }}", color = MaterialTheme.colorScheme.onBackground)
                    Text("${it.state.name}${it.detail?.let { d -> " — $d" } ?: ""}", color = if (it.state == OutState.UNCERTAIN || it.state == OutState.FAILED) Magenta else Cyan, fontSize = 12.sp)
                    Row {
                        if (it.state == OutState.UNCERTAIN) { TextButton(onClick = { scope.launch(Dispatchers.IO) { runCatching { repo.outbox.resendAnyway(it.draft.id) }; refresh() } }) { Text("Send again anyway", color = Magenta) }
                            TextButton(onClick = { repo.outbox.markSent(it.draft.id); refresh() }) { Text("It arrived", color = Cyan) } }
                        if (it.state == OutState.QUEUED) TextButton(onClick = { scope.launch(Dispatchers.IO) { runCatching { repo.flushOutbox() }; refresh() } }) { Text("Try now", color = Cyan) }
                        TextButton(onClick = { repo.outbox.discard(it.draft.id); refresh() }) { Text("Discard", color = Magenta) }
                    }
                }
            }
        }
    }
}
