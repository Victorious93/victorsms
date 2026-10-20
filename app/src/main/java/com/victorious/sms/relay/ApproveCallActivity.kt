package com.victorious.sms.relay

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.victorious.sms.ui.theme.Charcoal
import com.victorious.sms.ui.theme.Cyan
import com.victorious.sms.ui.theme.Magenta
import com.victorious.sms.ui.theme.VictorSMSTheme

/** Per-call approval for relay.call.place. Not exported. */
class ApproveCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val c = RemoteCallQueue.get(id)
        setContent {
            VictorSMSTheme {
                Column(Modifier.fillMaxSize().background(Charcoal).safeDrawingPadding().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (c == null || c.state != CallState.PENDING || System.currentTimeMillis() > c.expiresAtMs) {
                        Text("This call request is no longer pending.", color = Magenta)
                        Button(onClick = { finish() }) { Text("Close") }
                        return@Column
                    }
                    Text("Place call?", color = Cyan, fontWeight = FontWeight.Bold)
                    Text("Requested by paired phone: ${c.peerName}", color = MaterialTheme.colorScheme.onBackground)
                    Text("Number: ${c.to}", color = MaterialTheme.colorScheme.onBackground)
                    Text("Audio is not bridged back to that phone yet; the call happens on this phone.",
                        color = MaterialTheme.colorScheme.onBackground)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { RemoteCallQueue.approve(applicationContext, id); finish() }) { Text("Approve") }
                        OutlinedButton(onClick = { RemoteCallQueue.deny(applicationContext, id); finish() }) { Text("Deny", color = Magenta) }
                    }
                }
            }
        }
    }
    companion object { const val EXTRA_ID = "requestId" }
}
