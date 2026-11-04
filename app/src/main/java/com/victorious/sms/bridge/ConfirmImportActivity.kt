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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

/** On-device confirmation for one config.import. Not exported. Shows the caller and every key it would change. */
class ConfirmImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_IMPORT_ID).orEmpty()
        val p = ConfigOps.get(id)
        setContent {
            VictorSMSTheme {
                Column(
                    Modifier.fillMaxSize().background(Charcoal).safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (p == null || p.state != ImportState.PENDING || System.currentTimeMillis() > p.expiresAtMs) {
                        Text("This import is no longer pending.", color = Magenta)
                        Button(onClick = { finish() }) { Text("Close") }
                        return@Column
                    }
                    Text("Import settings?", color = Cyan, fontWeight = FontWeight.Bold)
                    Text("From app: ${p.callerPackage}", color = MaterialTheme.colorScheme.onBackground)
                    p.changes.forEach { (k, v) -> Text("• $k → $v", color = MaterialTheme.colorScheme.onBackground) }
                    Text("An import can never turn on remote commands or external control.", color = Magenta)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { ConfigOps.approve(applicationContext, id); finish() }) { Text("Import") }
                        OutlinedButton(onClick = { ConfigOps.deny(applicationContext, id); finish() }) { Text("Deny", color = Magenta) }
                    }
                }
            }
        }
    }

    companion object { const val EXTRA_IMPORT_ID = "importId" }
}
