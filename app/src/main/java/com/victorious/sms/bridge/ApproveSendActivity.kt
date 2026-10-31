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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.victorious.sms.ui.theme.Charcoal
import com.victorious.sms.ui.theme.Cyan
import com.victorious.sms.ui.theme.Magenta
import com.victorious.sms.ui.theme.VictorSMSTheme

/** On-device approval for one remote sms.send request. Not exported. */
class ApproveSendActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val p = RemoteSmsQueue.get(applicationContext, id)
        setContent {
            VictorSMSTheme {
                Column(
                    Modifier.fillMaxSize().background(Charcoal).safeDrawingPadding().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (p == null || p.state != SendState.PENDING || System.currentTimeMillis() > p.expiresAtMs) {
                        Text("This request is no longer pending.", color = Magenta)
                        Button(onClick = { finish() }) { Text("Close") }
                        return@Column
                    }
                    Text("Approve SMS?", color = Cyan, fontWeight = FontWeight.Bold)
                    Text("From app: ${p.caller.packageName}", color = MaterialTheme.colorScheme.onBackground)
                    Text("To: ${p.to}", color = MaterialTheme.colorScheme.onBackground)
                    Text("Message:\n${p.body}", color = MaterialTheme.colorScheme.onBackground)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { RemoteSmsQueue.approve(applicationContext, id); finish() }) { Text("Approve") }
                        OutlinedButton(onClick = { RemoteSmsQueue.deny(applicationContext, id); finish() }) { Text("Deny", color = Magenta) }
                    }
                }
            }
        }
    }

    companion object { const val EXTRA_REQUEST_ID = "requestId" }
}
