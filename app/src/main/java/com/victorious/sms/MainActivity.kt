package com.victorious.sms

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victorious.sms.ui.theme.AccentGradient
import com.victorious.sms.ui.theme.Charcoal
import com.victorious.sms.ui.theme.VictorSMSTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VictorSMSTheme { HomeScreen() } }
    }
}

@Composable
fun HomeScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(Charcoal),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                text = "VictorSMS",
                style = TextStyle(brush = AccentGradient, fontSize = 40.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(0.5f).height(4.dp).background(AccentGradient))
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Universal messenger on Matrix",
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

@Preview
@Composable
private fun HomePreview() { VictorSMSTheme { HomeScreen() } }
