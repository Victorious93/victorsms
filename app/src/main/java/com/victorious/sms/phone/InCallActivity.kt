package com.victorious.sms.phone

import android.os.Build
import android.os.Bundle
import android.telecom.Call
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victorious.sms.ui.theme.Charcoal
import com.victorious.sms.ui.theme.Cyan
import com.victorious.sms.ui.theme.Magenta
import com.victorious.sms.ui.theme.VictorSMSTheme

/** In-call screen: answer/end, mute, speaker, hold, keypad (DTMF). */
class InCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        setContent {
            VictorSMSTheme {
                val ui by CallManager.ui.collectAsState()
                var keypad by remember { mutableStateOf(false) }
                LaunchedEffect(ui) { if (ui == null) finish() }
                val u = ui ?: return@VictorSMSTheme
                val stateText = when (u.state) {
                    Call.STATE_RINGING -> "Incoming call"; Call.STATE_DIALING, Call.STATE_CONNECTING -> "Calling…"
                    Call.STATE_ACTIVE -> "Connected"; Call.STATE_HOLDING -> "On hold"; Call.STATE_DISCONNECTED -> "Ended"; else -> "…"
                }
                Column(Modifier.fillMaxSize().background(Charcoal).safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(u.label ?: u.number, color = MaterialTheme.colorScheme.onBackground, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    if (u.label != null) Text(u.number, color = Cyan)
                    Text(stateText, color = Cyan)
                    if (u.state == Call.STATE_RINGING) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { CallManager.answer() }) { Text("Answer") }
                            Button(onClick = { CallManager.end() }, colors = ButtonDefaults.buttonColors(containerColor = Magenta)) { Text("Decline") }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { CallManager.toggleMute() }) { Text(if (u.muted) "Unmute" else "Mute") }
                            OutlinedButton(onClick = { CallManager.toggleSpeaker() }) { Text(if (u.speaker) "Earpiece" else "Speaker") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { CallManager.toggleHold() }) { Text(if (u.state == Call.STATE_HOLDING) "Resume" else "Hold") }
                            OutlinedButton(onClick = { keypad = !keypad }) { Text(if (keypad) "Hide keypad" else "Keypad") }
                        }
                        if (keypad) Keypad { CallManager.dtmf(it) }
                        Button(onClick = { CallManager.end() }, colors = ButtonDefaults.buttonColors(containerColor = Magenta)) { Text("End call") }
                    }
                }
            }
        }
    }
}
