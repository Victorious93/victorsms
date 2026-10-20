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
import com.victorious.sms.matrix.MatrixManager
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
        val ok = withContext(Dispatchers.IO) { MatrixManager.get(ctx).restore() }
        onReady(ok)
    }
    Box(Modifier.fillMaxSize().background(Charcoal), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Cyan)
    }
}

@Composable
fun LoginScreen(onLoggedIn: () -> Unit, onHelp: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var hs by remember { mutableStateOf("matrix.org") }
    var user by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Page("VictorSMS", actions = { TextButton(onClick = onHelp) { Text("Help", color = Cyan) } }) {
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
        }
    }
}

@Composable
fun RoomListScreen(onOpen: (String) -> Unit, onHelp: () -> Unit, onLoggedOut: () -> Unit) {
    val ctx = LocalContext.current
    val mgr = MatrixManager.get(ctx)
    val rooms by mgr.rooms.collectAsState()
    val scope = rememberCoroutineScope()
    Page("Rooms", actions = {
        TextButton(onClick = onHelp) { Text("Help", color = Cyan) }
        TextButton(onClick = {
            scope.launch { withContext(Dispatchers.IO) { mgr.logout() }; onLoggedOut() }
        }) { Text("Log out", color = Magenta) }
    }) {
        if (rooms.isEmpty()) {
            Text("Syncing… no joined rooms yet.", color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(24.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rooms, key = { it.id }) { r ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(r.id) }.padding(16.dp)) {
                    Text(r.name, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
                    Text(r.id, color = Cyan.copy(alpha = 0.6f), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun RoomScreen(roomId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val room = remember(roomId) { MatrixManager.get(ctx).client?.getRoom(roomId) }
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
