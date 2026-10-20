package com.victorious.sms.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.victorious.sms.phone.LookupSettings
import com.victorious.sms.ui.theme.Magenta

@Composable
fun LookupSettingsScreen(onBack: () -> Unit) {
    val s = LookupSettings(LocalContext.current)
    var url by remember { mutableStateOf(s.pcUrl) }
    val onBg = MaterialTheme.colorScheme.onBackground
    ScreenColumn {
        Header("Caller lookup", onBack)
        Text("Order: 1) your contacts and blocklist, 2) your PC over the mesh, 3) an optional online provider.", color = onBg, fontSize = 13.sp)
        OutlinedTextField(url, { url = it; s.pcUrl = it }, label = { Text("PC lookup URL (empty = off)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Text("Example: http://100.64.0.2:8787/lookup. It gets ?number=… and should return {\"name\":\"…\"}.", color = onBg, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Online lookup (no provider configured)", color = onBg, modifier = Modifier.weight(1f))
            Switch(checked = false, enabled = false, onCheckedChange = {})
        }
        Text("Online lookup stays off. A paid caller-ID provider would need Don's approval before it is added.", color = Magenta, fontSize = 12.sp)
    }
}
