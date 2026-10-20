package com.victorious.sms.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val Charcoal = Color(0xFF16181C)
val CharcoalSurface = Color(0xFF22252B)
val Cyan = Color(0xFF00E5FF)
val Magenta = Color(0xFFFF2BD6)
val OnCharcoal = Color(0xFFE6E8EB)

val AccentGradient: Brush = Brush.linearGradient(listOf(Cyan, Magenta))

private val VictorColors = darkColorScheme(
    primary = Cyan,
    secondary = Magenta,
    background = Charcoal,
    surface = CharcoalSurface,
    onPrimary = Charcoal,
    onSecondary = Charcoal,
    onBackground = OnCharcoal,
    onSurface = OnCharcoal,
)

@Composable
fun VictorSMSTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = VictorColors, content = content)
}
