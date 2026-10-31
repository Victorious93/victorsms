package com.victorious.sms.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.victorious.sms.matrix.MatrixManager
import com.victorious.sms.matrix.MessageSource
import com.victorious.sms.matrix.RoomSummary
import com.victorious.sms.sms.SmsRepository
import com.victorious.sms.sms.SmsThread
import com.victorious.sms.matrix.TimelineModel
import com.victorious.sms.ui.theme.AccentGradient
import com.victorious.sms.ui.theme.Charcoal
import com.victorious.sms.ui.theme.CharcoalSurface
import com.victorious.sms.ui.theme.Cyan
import com.victorious.sms.ui.theme.Magenta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun Page(title: String, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(Charcoal).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = TextStyle(brush = AccentGradient, fontSize = 24.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            actions()
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(AccentGradient))
        content()
    }
}

@Composable
fun SplashScreen(onReady: (loggedIn: Boolean) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        val ok = withContext(Dispatchers.IO) { MatrixManager.get(ctx).restoreAll() }
        onReady(ok)
    }
    Box(Modifier.fillMaxSize().background(Charcoal), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Cyan)
    }
}

@Composable
fun LoginScreen(onLoggedIn: () -> Unit, onHelp: () -> Unit, onCancel: (() -> Unit)? = null, onSkip: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var hs by remember { mutableStateOf("matrix.org") }
    var user by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Page("VictorSMS", actions = {
        onCancel?.let { TextButton(onClick = it) { Text("Cancel", color = Cyan) } }
        TextButton(onClick = onHelp) { Text("Help", color = Cyan) }
    }) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Sign in to your Matrix homeserver", color = MaterialTheme.colorScheme.onBackground)
            OutlinedTextField(hs, { hs = it }, label = { Text("Homeserver") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it }, label = { Text("Username or @user:server") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = Magenta) }
            Button(
                enabled = !busy && hs.isNotBlank() && user.isNotBlank() && pw.isNotEmpty(),
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { MatrixManager.get(ctx).login(hs, user, pw) }
                            pw = ""
                            onLoggedIn()
                        } catch (e: Exception) {
                            // Never include credentials in the message.
                            error = "Login failed: ${e.javaClass.simpleName}"
                        } finally { busy = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Signing in…" else "Sign in") }
            onSkip?.let { TextButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("Skip: use SMS only", color = Cyan) } }
        }
    }
}

@Composable
fun RoomListScreen(
    onOpen: (accountId: String, roomId: String) -> Unit,
    onOpenSms: (threadId: Long, address: String) -> Unit,
    onOpenBot: (kind: String) -> Unit = {},
    onHelp: () -> Unit,
    onSettings: () -> Unit,
    onAddAccount: () -> Unit,
    onNoAccounts: () -> Unit,
) {
    val ctx = LocalContext.current
    val mgr = MatrixManager.get(ctx)
    val accounts by mgr.accountIds.collectAsState()
    val current by mgr.current.collectAsState()
    val roomsByAccount by mgr.rooms.collectAsState()
    val smsRepo = remember { SmsRepository(ctx) }
    var smsThreads by remember { mutableStateOf(emptyList<SmsThread>()) }
    var smsReady by remember { mutableStateOf(smsRepo.isDefaultSmsApp() && smsRepo.canRead()) }
    LaunchedEffect(smsReady) {
        while (true) {
            smsThreads = withContext(Dispatchers.IO) { smsRepo.threads() }
            kotlinx.coroutines.delay(5000)
        }
    }
    // Alias inbox: texts sent TO Don's Twilio alias numbers, polled from Twilio every 60 s.
    val twilio = remember { com.victorious.sms.twilio.TwilioCredentials(ctx) }
    var aliasThreads by remember { mutableStateOf(emptyList<com.victorious.sms.twilio.AliasMessage>()) }
    var filter by remember { mutableStateOf("all") }
    LaunchedEffect(Unit) {
        while (true) {
            if (twilio.isSet() && twilio.aliases.isNotEmpty()) {
                aliasThreads = withContext(Dispatchers.IO) {
                    runCatching {
                        val c = com.victorious.sms.twilio.TwilioClient(twilio)
                        twilio.aliases.flatMap { c.aliasMessages(it) }.distinctBy { it.alias + "|" + it.peer }
                    }.getOrDefault(aliasThreads)
                }
            } else aliasThreads = emptyList()
            kotlinx.coroutines.delay(60_000)
        }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        smsReady = smsRepo.isDefaultSmsApp() && smsRepo.canRead()
    }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permLauncher.launch(SMS_PERMISSIONS)
    }
    // Unified inbox: SMS threads (newest first) then Matrix rooms from ALL accounts.
    val bots = listOf(
        InboxEntry.Bot("summarizer", "Summarizer / triage", "Ask your PC LLM to summarize or triage"),
        InboxEntry.Bot("fleet", "Fleet (droidcommand-AI)", "Send a command to droidcommand-AI"),
    )
    val aliasEntries = aliasThreads.map { InboxEntry.Alias(it) }
    val entries: List<InboxEntry> = when (filter) {
        "sim" -> smsThreads.map { InboxEntry.Sms(it) }
        "alias" -> aliasEntries
        else -> bots + (smsThreads.map { InboxEntry.Sms(it) } + aliasEntries).sortedByDescending { it.dateMs } + roomsByAccount.values.flatten().map { InboxEntry.Matrix(it) }
    }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    Page("Inbox", actions = {
        Box {
            TextButton(onClick = { menu = true }) { Text(current ?: "Accounts", color = Cyan, maxLines = 1) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                accounts.forEach { id ->
                    DropdownMenuItem(text = { Text((if (id == current) "● " else "") + id) },
                        onClick = { mgr.switchTo(id); menu = false })
                }
                DropdownMenuItem(text = { Text("Add account") }, onClick = { menu = false; onAddAccount() })
                current?.let { cur ->
                    DropdownMenuItem(text = { Text("Remove / log out $cur", color = Magenta) }, onClick = {
                        menu = false
                        scope.launch {
                            withContext(Dispatchers.IO) { mgr.remove(cur) }
                            if (mgr.accountIds.value.isEmpty()) onNoAccounts()
                        }
                    })
                }
                DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onSettings() })
                DropdownMenuItem(text = { Text("Help") }, onClick = { menu = false; onHelp() })
            }
        }
    }) {
        if (!smsReady) {
            Row(Modifier.fillMaxWidth().background(CharcoalSurface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Make VictorSMS your SMS app to see texts here.", color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val i = smsRepo.requestDefaultRoleIntent()
                    if (i != null) roleLauncher.launch(i) else permLauncher.launch(SMS_PERMISSIONS)
                }) { Text("Set up", color = Cyan) }
            }
        }
        if (aliasThreads.isNotEmpty() || twilio.aliases.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                listOf("all" to "All", "sim" to "SIM", "alias" to "Alias").forEach { (k, l) ->
                    TextButton(onClick = { filter = k }) { Text(if (filter == k) "[$l]" else l, color = if (filter == k) Magenta else Cyan) }
                }
            }
        }
        if (entries.isEmpty()) {
            Text("No conversations yet.", color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(24.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(entries, key = { it.key }) { e ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        when (e) {
                            is InboxEntry.Sms -> onOpenSms(e.t.threadId, e.t.address)
                            is InboxEntry.Matrix -> onOpen(e.r.accountId, e.r.id)
                            is InboxEntry.Bot -> onOpenBot(e.kind)
                            is InboxEntry.Alias -> onOpenSms(-1L, e.m.peer)
                        }
                    }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(e.subtitle, color = Cyan.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1)
                    }
                    Text(
                        e.badge, color = Charcoal, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(if (e is InboxEntry.Alias) Magenta else if (e.source == MessageSource.SMS) Cyan else Magenta, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun RoomScreen(accountId: String, roomId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val room = remember(roomId) { runCatching { MatrixManager.get(ctx).client(accountId)?.getRoom(roomId) }.getOrNull() }
    val model = remember(roomId) { room?.let { TimelineModel(it) } }
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(model) { model?.let { withContext(Dispatchers.IO) { it.open() } } }
    DisposableEffect(model) { onDispose { model?.close() } }
    Page(room?.displayName() ?: roomId, actions = { TextButton(onClick = onBack) { Text("Back", color = Cyan) } }) {
        if (model == null) {
            Text("Room not available.", color = Magenta, modifier = Modifier.padding(24.dp)); return@Page
        }
        val msgs by model.messages.collectAsState()
        Column(Modifier.fillMaxSize().imePadding()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true) {
                items(msgs.asReversed(), key = { it.key }) { m ->
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start,
                    ) {
                        Text(m.sender, color = if (m.mine) Cyan else Magenta, fontSize = 11.sp)
                        Text(
                            m.body, color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.background(CharcoalSurface, RoundedCornerShape(12.dp)).padding(10.dp),
                        )
                    }
                }
                item { TextButton(onClick = { scope.launch(Dispatchers.IO) { model.loadMore() } }) { Text("Load older", color = Cyan) } }
            }
            AssistBar(room?.displayName() ?: roomId, { msgs.map { it.mine to it.body } }, draft) { draft = it }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft, { draft = it }, placeholder = { Text("Message") }, modifier = Modifier.weight(1f))
                Spacer(Modifier.padding(4.dp))
                Button(enabled = draft.isNotBlank(), onClick = {
                    val text = draft; draft = ""
                    scope.launch(Dispatchers.IO) { runCatching { model.send(text) } }
                }) { Text("Send") }
            }
        }
    }
}

@Composable
fun HelpScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val text = remember {
        runCatching { ctx.assets.open("MANUAL.md").bufferedReader().use { it.readText() } }
            .getOrDefault("Manual not bundled in this build.")
    }
    Page("Help / Manual", actions = { TextButton(onClick = onBack) { Text("Back", color = Cyan) } }) {
        Text(
            text, color = MaterialTheme.colorScheme.onBackground, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        )
    }
}

val SMS_PERMISSIONS = buildList {
    add(android.Manifest.permission.READ_SMS); add(android.Manifest.permission.SEND_SMS)
    add(android.Manifest.permission.RECEIVE_SMS)
    if (android.os.Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

sealed class InboxEntry {
    abstract val key: String; abstract val title: String; abstract val subtitle: String; abstract val source: MessageSource; open val badge: String get() = source.label; open val dateMs: Long get() = 0L
    data class Sms(val t: SmsThread) : InboxEntry() {
        override val key get() = "sms:${t.threadId}"
        override val title get() = t.address
        override val subtitle get() = t.snippet
        override val source get() = MessageSource.SMS
        override val dateMs get() = t.dateMs
    }
    /** Latest text with someone on one of Don's Twilio alias numbers. */
    data class Alias(val m: com.victorious.sms.twilio.AliasMessage) : InboxEntry() {
        override val key get() = "alias:${m.alias}:${m.peer}"
        override val title get() = m.peer
        override val subtitle get() = m.body
        override val source get() = MessageSource.SMS
        override val badge get() = "Alias …" + m.alias.takeLast(4)
        override val dateMs get() = m.dateMs
    }
    data class Matrix(val r: RoomSummary) : InboxEntry() {
        override val key get() = "mx:${r.accountId}:${r.id}"
        override val title get() = r.name
        override val subtitle get() = r.accountId
        override val source get() = r.source
    }
    data class Bot(val kind: String, override val title: String, override val subtitle: String) : InboxEntry() {
        override val key get() = "bot:$kind"
        override val source get() = MessageSource.MATRIX
        override val badge get() = "Bot"
    }
}
