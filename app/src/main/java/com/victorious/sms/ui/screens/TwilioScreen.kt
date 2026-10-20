package com.victorious.sms.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victorious.sms.twilio.AvailableNumber
import com.victorious.sms.twilio.SipCalling
import com.victorious.sms.twilio.SipDomain
import com.victorious.sms.twilio.TwilioClient
import com.victorious.sms.twilio.TwilioCredentials
import com.victorious.sms.twilio.TwilioNumber
import com.victorious.sms.ui.theme.Cyan
import com.victorious.sms.ui.theme.Magenta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TwilioScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val creds = remember { TwilioCredentials(ctx) }
    val client = remember { TwilioClient(creds) }
    val scope = rememberCoroutineScope()
    val onBg = MaterialTheme.colorScheme.onBackground
    var sid by remember { mutableStateOf(creds.accountSid) }
    var keySid by remember { mutableStateOf(creds.apiKeySid) }
    var secret by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf(creds.aliasNumber) }
    var numbers by remember { mutableStateOf(emptyList<TwilioNumber>()) }
    var domains by remember { mutableStateOf(emptyList<SipDomain>()) }
    var info by remember { mutableStateOf<String?>(null) }
    var domPrefix by remember { mutableStateOf("") }
    var sipUser by remember { mutableStateOf("") }
    var sipPass by remember { mutableStateOf("") }
    var area by remember { mutableStateOf("") }
    var available by remember { mutableStateOf(emptyList<AvailableNumber>()) }
    var buying by remember { mutableStateOf<Pair<AvailableNumber, String?>?>(null) }
    var to by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }

    fun run(label: String, block: suspend () -> String) = scope.launch {
        info = "$label…"
        info = runCatching { withContext(Dispatchers.IO) { block() } }.getOrElse { "Failed: ${it.message ?: it.javaClass.simpleName}" }
    }

    ScreenColumn {
        Header("Twilio alias number", onBack)
        Text("Use a real second number that you own in your Twilio account. Credentials are stored encrypted on this phone.", color = onBg, fontSize = 13.sp)
        OutlinedTextField(sid, { sid = it }, label = { Text("Account SID (AC…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(keySid, { keySid = it }, label = { Text("API key SID (SK…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(secret, { secret = it }, label = { Text(if (creds.apiKeySecret.isNotBlank()) "API key secret (saved; type to replace)" else "API key secret") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { creds.accountSid = sid; creds.apiKeySid = keySid; if (secret.isNotBlank()) creds.apiKeySecret = secret; secret = ""; info = "Saved." }) { Text("Save") }
            TextButton(onClick = { creds.clear(); sid = ""; keySid = ""; alias = ""; info = "Credentials removed." }) { Text("Remove", color = Magenta) }
        }
        info?.let { Text(it, color = Cyan, fontSize = 12.sp) }

        Text("Your numbers", color = Cyan, fontWeight = FontWeight.SemiBold)
        Button(enabled = creds.isSet(), onClick = { run("Loading") { numbers = client.listNumbers(); "${numbers.size} number(s)." } }) { Text("Load my numbers") }
        numbers.forEach { n ->
            Row(Modifier.fillMaxWidth()) {
                Text("${n.phoneNumber}  ${n.friendlyName}", color = onBg, modifier = Modifier.weight(1f))
                TextButton(onClick = { creds.aliasNumber = n.phoneNumber; alias = n.phoneNumber }) {
                    Text(if (alias == n.phoneNumber) "Alias ✓" else "Use as alias", color = Cyan)
                }
            }
        }

        Text("Send a text from your alias", color = Cyan, fontWeight = FontWeight.SemiBold)
        Text(if (alias.isBlank()) "Pick an alias number first." else "From $alias", color = onBg, fontSize = 12.sp)
        OutlinedTextField(to, { to = it }, label = { Text("To") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(body, { body = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = creds.isSet() && alias.isNotBlank() && to.isNotBlank() && body.isNotBlank(), onClick = {
            val (t, b) = to to body
            run("Sending") { client.sendSms(alias, t, b); body = ""; "Sent via Twilio (standard Twilio messaging charges apply)." }
        }) { Text("Send from alias") }

        Text("SIP (for calls from your alias)", color = Cyan, fontWeight = FontWeight.SemiBold)
        Button(enabled = creds.isSet(), onClick = { run("Loading") { domains = client.listSipDomains(); "${domains.size} SIP domain(s)." } }) { Text("Load SIP domains") }
        domains.forEach { Text(it.domainName, color = onBg, fontSize = 13.sp) }
        OutlinedTextField(domPrefix, { domPrefix = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '-' } }, label = { Text("New domain name (….sip.twilio.com)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(sipUser, { sipUser = it }, label = { Text("SIP username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(sipPass, { sipPass = it }, label = { Text("SIP password (Twilio: 12+ chars, mixed case, a digit)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(enabled = creds.isSet() && domPrefix.isNotBlank() && sipUser.isNotBlank() && sipPass.length >= 12, onClick = {
            val (d, u, p) = Triple(domPrefix, sipUser, sipPass)
            run("Creating") { val r = client.createSipDomainWithCredentials(d, u, p); sipPass = ""; domains = client.listSipDomains(); "Created ${r.domainName}." }
        }) { Text("Create SIP domain + credentials") }
        Text(
            if (SipCalling.platformSipSupported(ctx)) "This phone reports built-in SIP support, but SIP calling in VictorSMS is not built yet."
            else "SIP calling is not built yet (no licence-compatible SIP stack chosen). Texts work now.",
            color = Magenta, fontSize = 12.sp,
        )

        Text("Get a new number", color = Cyan, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(area, { area = it.filter(Char::isDigit).take(3) }, label = { Text("US area code (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = creds.isSet(), onClick = { run("Searching") { available = client.searchAvailable("US", area); "${available.size} available." } }) { Text("Search") }
        available.take(10).forEach { a ->
            Row(Modifier.fillMaxWidth()) {
                Text("${a.phoneNumber}  ${a.locality}", color = onBg, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch { buying = a to withContext(Dispatchers.IO) { client.localNumberMonthlyPrice("US") } }
                }) { Text("Buy…", color = Magenta) }
            }
        }
    }

    buying?.let { (a, price) ->
        AlertDialog(
            onDismissRequest = { buying = null },
            title = { Text("Buy ${a.phoneNumber}?") },
            text = {
                Text("This charges your Twilio account " + (price?.let { "about $it per month" } ?: "a monthly fee (price could not be loaded; check twilio.com/pricing)") +
                    ", plus usage. Nothing is bought unless you tap Buy.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = a.phoneNumber; buying = null
                    run("Buying") { val b = client.buyNumber(n); numbers = client.listNumbers(); "Bought ${b.phoneNumber}." }
                }) { Text("Buy", color = Magenta) }
            },
            dismissButton = { TextButton(onClick = { buying = null }) { Text("Cancel") } },
        )
    }
}
