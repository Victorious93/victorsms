package com.victorious.sms.ui.screens

import android.app.role.RoleManager
import android.content.ContentValues
import android.os.Build
import android.provider.Telephony
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victorious.sms.block.BlockAction
import com.victorious.sms.block.Blocklist
import com.victorious.sms.block.SpamFolder
import com.victorious.sms.block.SpamPattern
import com.victorious.sms.ui.theme.Charcoal
import com.victorious.sms.ui.theme.Cyan
import com.victorious.sms.ui.theme.Magenta

@Composable
internal fun Header(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Cyan, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClick = onBack) { Text("Back", color = Cyan) }
    }
}

@Composable
internal fun ScreenColumn(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(Charcoal).safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

@Composable
fun BlockingScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val bl = remember { Blocklist(ctx) }
    var numbers by remember { mutableStateOf(bl.numbers()) }
    var patterns by remember { mutableStateOf(bl.patterns()) }
    var num by remember { mutableStateOf("") }
    var pat by remember { mutableStateOf("") }
    var isKeyword by remember { mutableStateOf(false) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val onBg = MaterialTheme.colorScheme.onBackground
    ScreenColumn {
        Header("Blocking & spam", onBack)
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = ctx.getSystemService(RoleManager::class.java)
            val held = rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
            Text(if (held) "Call screening: on" else "Call screening is off. Calls are not filtered.", color = if (held) Cyan else Magenta)
            if (!held && rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                Button(onClick = { roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)) }) { Text("Turn on call screening") }
            }
        } else Text("Call screening needs Android 10 or newer.", color = Magenta)

        Text("Numbers", color = Cyan, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(num, { num = it }, label = { Text("Phone number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BlockAction.entries.forEach { a ->
                Button(enabled = num.isNotBlank(), onClick = { bl.set(num, a); numbers = bl.numbers(); num = "" }) {
                    Text(a.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 12.sp)
                }
            }
        }
        numbers.forEach { (n, a) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$n  ·  ${a.name.lowercase()}", color = onBg, modifier = Modifier.weight(1f))
                TextButton(onClick = { bl.remove(n); numbers = bl.numbers() }) { Text("Remove", color = Magenta) }
            }
        }

        Text("Spam patterns", color = Cyan, fontWeight = FontWeight.SemiBold)
        Text("Number patterns use * for any digits (e.g. +1900*). Keywords filter texts into the Spam folder.", color = onBg, fontSize = 12.sp)
        OutlinedTextField(pat, { pat = it }, label = { Text("Pattern") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = isKeyword, onCheckedChange = { isKeyword = it }); Text("Text keyword (not a number)", color = onBg, fontSize = 13.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(BlockAction.BLOCK, BlockAction.SILENCE).forEach { a ->
                Button(enabled = pat.isNotBlank(), onClick = {
                    bl.setPatterns(patterns + SpamPattern(pat.trim(), isKeyword, a)); patterns = bl.patterns(); pat = ""
                }) { Text("Add as ${a.name.lowercase()}", fontSize = 12.sp) }
            }
        }
        patterns.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${if (p.isKeyword) "text" else "number"}: ${p.pattern}  ·  ${p.action.name.lowercase()}", color = onBg, modifier = Modifier.weight(1f))
                TextButton(onClick = { bl.setPatterns(patterns - p); patterns = bl.patterns() }) { Text("Remove", color = Magenta) }
            }
        }
    }
}

@Composable
fun SpamFolderScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val folder = remember { SpamFolder(ctx) }
    var items by remember { mutableStateOf(folder.all()) }
    val onBg = MaterialTheme.colorScheme.onBackground
    ScreenColumn {
        Header("Spam folder", onBack)
        if (items.isEmpty()) Text("No filtered texts.", color = onBg)
        else TextButton(onClick = { folder.clear(); items = folder.all() }) { Text("Delete all", color = Magenta) }
        items.forEach { m ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(m.address, color = onBg, fontWeight = FontWeight.SemiBold)
                Text(m.body, color = onBg, fontSize = 13.sp)
                Row {
                    TextButton(onClick = {
                        // Not spam: move to the system inbox and allow the sender.
                        runCatching {
                            ctx.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
                                put(Telephony.Sms.ADDRESS, m.address); put(Telephony.Sms.BODY, m.body)
                                put(Telephony.Sms.DATE, m.dateMs); put(Telephony.Sms.READ, 1)
                            })
                        }
                        Blocklist(ctx).set(m.address, BlockAction.ALLOW)
                        folder.remove(m); items = folder.all()
                    }) { Text("Not spam", color = Cyan) }
                    TextButton(onClick = { folder.remove(m); items = folder.all() }) { Text("Delete", color = Magenta) }
                }
            }
        }
    }
}
