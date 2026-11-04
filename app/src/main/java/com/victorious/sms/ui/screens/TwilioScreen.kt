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
import com.victorious.sms.twilio.VoiceCalling
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    var aliases by remember { mutableStateOf(creds.aliases) }
    var alias by remember { mutableStateOf(aliases.firstOrNull().orEmpty()) }
    var myPhone by remember { mutableStateOf(creds.myPhone) }
    var defaultId by remember { mutableStateOf(creds.defaultIdentity) }
    var confirmSend by remember { mutableStateOf<Triple<String, String, String?>?>(null) }
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
    var pendingBody by remember { mutableStateOf("") }
    var confirmSip by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var shimUrl by remember { mutableStateOf("") }
    var appSid by remember { mutableStateOf(creds.twimlAppSid) }
    var pushSid by remember { mutableStateOf(creds.pushCredentialSid) }
    var fcmProject by remember { mutableStateOf(creds.fcmProjectId) }
    var fcmApp by remember { mutableStateOf(creds.fcmAppId) }
    var fcmKey by remember { mutableStateOf(creds.fcmApiKey) }
    var fcmSender by remember { mutableStateOf(creds.fcmSenderId) }
    var confirmApp by remember { mutableStateOf(false) }
    var confirmRoute by remember { mutableStateOf<String?>(null) }
    var callTo by remember { mutableStateOf("") }
    var confirmCall by remember { mutableStateOf<Triple<String, String, String?>?>(null) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (!ok) info = "Microphone denied: alias calls need it." }

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
            TextButton(onClick = { creds.clear(); sid = ""; keySid = ""; alias = ""; aliases = emptySet(); myPhone = ""; info = "Credentials removed." }) { Text("Remove", color = Magenta) }
        }
        info?.let { Text(it, color = Cyan, fontSize = 12.sp) }

        Text("Your numbers", color = Cyan, fontWeight = FontWeight.SemiBold)
        Button(enabled = creds.isSet(), onClick = { run("Loading") { numbers = client.listNumbers(); "${numbers.size} number(s)." } }) { Text("Load my numbers") }
        numbers.forEach { n ->
            Row(Modifier.fillMaxWidth()) {
                Text("${n.phoneNumber}  ${n.friendlyName}", color = onBg, modifier = Modifier.weight(1f))
                val on = n.phoneNumber in aliases
                TextButton(onClick = {
                    aliases = if (on) aliases - n.phoneNumber else aliases + n.phoneNumber
                    creds.aliases = aliases
                    if (on && defaultId == com.victorious.sms.twilio.Identity.Alias(n.phoneNumber)) { defaultId = com.victorious.sms.twilio.Identity.Sim; creds.defaultIdentity = defaultId }
                    if (alias !in aliases) alias = aliases.firstOrNull().orEmpty()
                }) { Text(if (on) "Alias ✓" else "Use as alias", color = Cyan) }
            }
        }
        Text("Only numbers listed here (fetched from your Twilio account) can be used as an alias. Several are allowed.", color = onBg, fontSize = 12.sp)

        Text("Default identity", color = Cyan, fontWeight = FontWeight.SemiBold)
        (listOf(com.victorious.sms.twilio.Identity.Sim) + aliases.sorted().map { com.victorious.sms.twilio.Identity.Alias(it) }).forEach { o ->
            TextButton(onClick = { defaultId = o; creds.defaultIdentity = o }) {
                Text((if (o == defaultId) "● " else "○ ") + com.victorious.sms.twilio.IdentityResolver.label(o), color = onBg)
            }
        }
        Text("Each SMS thread can override this (Sending as → Change).", color = onBg, fontSize = 12.sp)

        Text("Masked calls", color = Cyan, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(myPhone, { myPhone = it }, label = { Text("Your real phone (+1…), rung first") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { creds.myPhone = myPhone; info = "Saved." }) { Text("Save phone") }
        Text("A masked call rings your phone from Twilio, then connects the other person showing your alias. Billed per minute for both legs.", color = onBg, fontSize = 12.sp)

        Text("Send a text from your alias", color = Cyan, fontWeight = FontWeight.SemiBold)
        if (aliases.isEmpty()) Text("Pick an alias number first.", color = onBg, fontSize = 12.sp)
        aliases.sorted().forEach { a -> TextButton(onClick = { alias = a }) { Text((if (a == alias) "● From " else "○ From ") + a, color = onBg, fontSize = 12.sp) } }
        OutlinedTextField(to, { to = it }, label = { Text("To") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(body, { body = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = creds.isSet() && alias.isNotBlank() && to.isNotBlank() && body.isNotBlank(), onClick = {
            val (t, b) = to to body; val a = alias
            scope.launch { confirmSend = Triple(a, t, withContext(Dispatchers.IO) { client.smsPrice("US") }) }
            pendingBody = b
        }) { Text("Send from alias") }

        Text("SIP (for calls from your alias)", color = Cyan, fontWeight = FontWeight.SemiBold)
        Button(enabled = creds.isSet(), onClick = { run("Loading") { domains = client.listSipDomains(); "${domains.size} SIP domain(s)." } }) { Text("Load SIP domains") }
        domains.forEach { Text(it.domainName, color = onBg, fontSize = 13.sp) }
        OutlinedTextField(domPrefix, { domPrefix = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '-' } }, label = { Text("New domain name (….sip.twilio.com)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(sipUser, { sipUser = it }, label = { Text("SIP username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(sipPass, { sipPass = it }, label = { Text("SIP password (Twilio: 12+ chars, mixed case, a digit)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(enabled = creds.isSet() && domPrefix.isNotBlank() && sipUser.isNotBlank() && sipPass.length >= 12, onClick = {
            confirmSip = Triple(domPrefix, sipUser, sipPass)
        }) { Text("Create SIP domain + credentials…") }
        Text("Optional: a SIP domain lets a desk phone or softphone register to your Twilio account. VictorSMS itself calls over the Twilio Voice SDK below.", color = onBg, fontSize = 12.sp)

        Text("Alias calls (Twilio Voice)", color = Cyan, fontWeight = FontWeight.SemiBold)
        Text("Real VoIP calls from this phone showing your alias. Needs pc/voice_shim.py reachable over https (see Help).", color = onBg, fontSize = 12.sp)
        OutlinedTextField(shimUrl, { shimUrl = it.trim() }, label = { Text("Voice shim base URL (https://…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = creds.isSet() && shimUrl.startsWith("https://"), onClick = { confirmApp = true }) { Text("Create TwiML App…") }
        OutlinedTextField(appSid, { appSid = it }, label = { Text("TwiML App SID (AP…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        aliases.sorted().forEach { a ->
            TextButton(enabled = shimUrl.startsWith("https://"), onClick = { confirmRoute = a }) { Text("Route incoming calls on $a to this app…", color = Cyan, fontSize = 12.sp) }
        }
        OutlinedTextField(pushSid, { pushSid = it }, label = { Text("Push Credential SID (CR…, incoming calls)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(fcmProject, { fcmProject = it }, label = { Text("Firebase project ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(fcmApp, { fcmApp = it }, label = { Text("Firebase app ID (1:…:android:…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(fcmKey, { fcmKey = it }, label = { Text("Firebase API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(fcmSender, { fcmSender = it }, label = { Text("Firebase sender ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                creds.twimlAppSid = appSid; creds.pushCredentialSid = pushSid
                creds.fcmProjectId = fcmProject; creds.fcmAppId = fcmApp; creds.fcmApiKey = fcmKey; creds.fcmSenderId = fcmSender
                if (!VoiceCalling.micGranted(ctx)) micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                info = "Saved."
            }) { Text("Save") }
            Button(enabled = creds.isSet(), onClick = { info = "Registering…"; VoiceCalling.registerIncoming(ctx) { m -> info = m } }) { Text("Enable incoming") }
        }
        Text("After the first call, allow \"VictorSMS alias (Twilio)\" under Settings → Calling accounts if Android asks.", color = onBg, fontSize = 12.sp)
        OutlinedTextField(callTo, { callTo = it }, label = { Text("Call (+E.164)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = creds.isSet() && alias.isNotBlank() && callTo.isNotBlank() && creds.twimlAppSid.startsWith("AP"), onClick = {
            val (a, t) = alias to callTo
            scope.launch { confirmCall = Triple(a, t, withContext(Dispatchers.IO) { client.voicePricePerMinute(t) }) }
        }) { Text("Call from alias…") }

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

    confirmSend?.let { (a, t, price) ->
        AlertDialog(
            onDismissRequest = { confirmSend = null },
            title = { Text("Send from alias …${a.takeLast(4)}?") },
            text = { Text("Charged to your Twilio account: " + (price ?: "price could not be loaded (see twilio.com/pricing)") + ". Nothing is sent unless you tap Send.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSend = null; val b = pendingBody
                    run("Sending") { client.sendMessage(a, t, b); body = ""; "Sent via Twilio." }
                }) { Text("Send", color = Magenta) }
            },
            dismissButton = { TextButton(onClick = { confirmSend = null }) { Text("Cancel") } },
        )
    }

    confirmSip?.let { (d, u, pw) ->
        AlertDialog(
            onDismissRequest = { confirmSip = null },
            title = { Text("Create $d.sip.twilio.com?") },
            text = { Text("The domain and credential list have no setup fee, but SIP registrations and every SIP call minute are billed to your Twilio account (Twilio does not expose this price over the API; see twilio.com/en-us/voice/pricing). Nothing is created unless you tap Create.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSip = null
                    run("Creating") { val r = client.createSipDomainWithCredentials(d, u, pw); sipPass = ""; domains = client.listSipDomains(); "Created ${r.domainName}." }
                }) { Text("Create", color = Magenta) }
            },
            dismissButton = { TextButton(onClick = { confirmSip = null }) { Text("Cancel") } },
        )
    }

    if (confirmApp) {
        AlertDialog(
            onDismissRequest = { confirmApp = false },
            title = { Text("Create a TwiML App?") },
            text = { Text("A TwiML App has no monthly fee. Calls made through it are billed per minute (shown before each call). Voice URL: ${shimUrl.trimEnd('/')}/voice. Nothing is created unless you tap Create.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmApp = false; val u = shimUrl.trimEnd('/') + "/voice"
                    run("Creating") { val sid = client.createTwimlApp(u); creds.twimlAppSid = sid; appSid = sid; "TwiML App $sid created." }
                }) { Text("Create", color = Magenta) }
            },
            dismissButton = { TextButton(onClick = { confirmApp = false }) { Text("Cancel") } },
        )
    }

    confirmRoute?.let { a ->
        AlertDialog(
            onDismissRequest = { confirmRoute = null },
            title = { Text("Route incoming calls on $a?") },
            text = { Text("Changes this number's Voice URL to ${shimUrl.trimEnd('/')}/incoming, replacing whatever it does now. No setup fee; incoming minutes and the app leg are billed per minute by Twilio (see twilio.com/en-us/voice/pricing).") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRoute = null; val u = shimUrl.trimEnd('/') + "/incoming"
                    run("Updating") { client.setNumberVoiceUrl(a, u); "Incoming calls on $a now go to VictorSMS." }
                }) { Text("Route", color = Magenta) }
            },
            dismissButton = { TextButton(onClick = { confirmRoute = null }) { Text("Cancel") } },
        )
    }

    confirmCall?.let { (a, t, price) ->
        AlertDialog(
            onDismissRequest = { confirmCall = null },
            title = { Text("Call $t from alias …${a.takeLast(4)}?") },
            text = { Text("Charged to your Twilio account: " + (price ?: "price could not be loaded (see twilio.com/en-us/voice/pricing)") + ", plus the app-leg minute rate. Nothing is dialled unless you tap Call.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCall = null
                    run("Calling") { VoiceCalling.placeCall(ctx, a, t).getOrThrow(); "Calling via Twilio…" }
                }) { Text("Call", color = Magenta) }
            },
            dismissButton = { TextButton(onClick = { confirmCall = null }) { Text("Cancel") } },
        )
    }
}
