package com.victorious.sms.bridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.victorious.sms.sms.SmsSubscriptions
import com.victorious.sms.ui.screens.SmsSimPicker
import com.victorious.sms.ui.theme.Charcoal
import com.victorious.sms.ui.theme.Cyan
import com.victorious.sms.ui.theme.Magenta
import com.victorious.sms.ui.theme.VictorSMSTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** On-device approval for one remote sms.send request. Not exported. */
class ApproveSendActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val p = RemoteSmsQueue.get(applicationContext, id)
        setContent {
            VictorSMSTheme {
                val scope = rememberCoroutineScope()
                var sim by rememberSaveable { mutableStateOf(p?.let { SmsSubscriptions(applicationContext).preferred(it.to) }) }
                var busy by remember { mutableStateOf(false) }
                var outcome by remember { mutableStateOf<SendState?>(null) }
                Column(
                    Modifier.fillMaxSize().background(Charcoal).safeDrawingPadding().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val result = outcome
                    if (result != null) {
                        Text(when (result) {
                            SendState.DENIED -> "This caller no longer has SMS permission."
                            SendState.EXPIRED -> "This request expired."
                            SendState.FAILED -> "SMS could not be sent. Check the SIM and SMS permission."
                            else -> "Open SMS activity for carrier status."
                        }, color = MaterialTheme.colorScheme.onBackground)
                        Button(onClick = { finish() }) { Text("Close") }
                        return@Column
                    }
                    if (p == null || (!busy && (p.state != SendState.PENDING || System.currentTimeMillis() >= p.expiresAtMs))) {
                        Text("This request is no longer pending.", color = Magenta)
                        Button(onClick = { finish() }) { Text("Close") }
                        return@Column
                    }
                    Text("Approve SMS?", color = Cyan, fontWeight = FontWeight.Bold)
                    Text("From app: ${p.caller.packageName}", color = MaterialTheme.colorScheme.onBackground)
                    Text("To: ${p.to}", color = MaterialTheme.colorScheme.onBackground)
                    Text("Message:\n${p.body}", color = MaterialTheme.colorScheme.onBackground)
                    SmsSimPicker(sim, onSelected = { sim = it }, enabled = !busy)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(enabled = !busy, onClick = {
                            if (busy) return@Button
                            val selected = sim
                            busy = true
                            scope.launch {
                                try {
                                    outcome = withContext(Dispatchers.IO) { RemoteSmsQueue.approve(applicationContext, id, selected) }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    outcome = SendState.UNKNOWN
                                } finally {
                                    busy = false
                                }
                            }
                        }) { Text("Approve") }
                        OutlinedButton(enabled = !busy, onClick = { RemoteSmsQueue.deny(applicationContext, id); finish() }) { Text("Deny", color = Magenta) }
                    }
                }
            }
        }
    }

    companion object { const val EXTRA_REQUEST_ID = "requestId" }
}
