package com.victorious.sms.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victorious.sms.fleet.SmsRemoteTriggerSettings
import com.victorious.sms.ui.theme.Cyan
import com.victorious.sms.ui.theme.Magenta

@Composable
fun SmsTriggerScreen(onBack: () -> Unit) {
    val s = SmsRemoteTriggerSettings(LocalContext.current)
    var on by remember { mutableStateOf(s.enabled) }
    var word by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var list by remember { mutableStateOf(s.whitelist().toList()) }
    val onBg = MaterialTheme.colorScheme.onBackground
    ScreenColumn {
        Header("SMS remote-command trigger", onBack)
        Text("When an SMS arrives from a whitelisted sender AND contains your secret code word, VictorSMS forwards it to droidcommand-AI. VictorSMS never runs the command. Off by default. Needs External control ON and droidcommand-AI approved.",
            color = onBg, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enable SMS trigger", color = onBg, modifier = Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = { on = it; s.enabled = it })
        }
        Text("Code word (stored encrypted, never logged or exported)", color = Cyan, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(word, { word = it }, label = { Text(if (s.codeWord.isNotBlank()) "Type a new code word to replace" else "Code word") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = word.isNotBlank(), onClick = { s.codeWord = word; word = "" }) { Text("Save code word") }
            TextButton(onClick = { s.codeWord = ""; word = "" }) { Text("Clear", color = Magenta) }
        }
        Text(if (s.codeWord.isNotBlank()) "A code word is set." else "No code word set (trigger never matches).", color = onBg, fontSize = 12.sp)

        Text("Sender whitelist", color = Cyan, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(number, { number = it }, label = { Text("Phone number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = number.isNotBlank(), onClick = {
            s.setWhitelist(list.toSet() + number); list = s.whitelist().toList(); number = ""
        }) { Text("Add") }
        list.forEach { n ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n, color = onBg, modifier = Modifier.weight(1f))
                TextButton(onClick = { s.setWhitelist(list.toSet() - n); list = s.whitelist().toList() }) { Text("Remove", color = Magenta) }
            }
        }
        if (list.isEmpty()) Text("Empty whitelist: no SMS can trigger.", color = Magenta, fontSize = 12.sp)
    }
}
