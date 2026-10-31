package com.victorious.sms.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val Charcoal = Color(0xFF16181C)
val CharcoalSurface = Color(0xFF22252B)
val Cyan = Color(0xFF00E5FF)
val Magenta = Color(0xFFFF2BD6)
val OnCharcoal = Color(0xFFE6E8EB)

val AccentGradient: Brush = Brush.linearGradient(listOf(Cyan, Magenta))

private val NeonColors = darkColorScheme(
    primary = Cyan,
    secondary = Magenta,
    background = Charcoal,
    surface = CharcoalSurface,
    onPrimary = Charcoal,
    onSecondary = Charcoal,
    onBackground = OnCharcoal,
    onSurface = OnCharcoal,
)

/** Maps palette keys to M3 roles per FLEET_THEME.md; a null key keeps the neon default for its roles. */
internal fun schemeFor(p: FleetPalette): ColorScheme {
    if (p == FleetPalette.NONE) return NeonColors
    val d = NeonColors
    fun Long?.or(def: Color) = if (this == null) def else Color(this.toInt())
    return d.copy(
        background = p.background.or(d.background),
        onPrimary = p.background.or(d.onPrimary),
        onSecondary = p.background.or(d.onSecondary),
        onError = p.background.or(d.onError),
        surface = p.surface.or(d.surface),
        primary = p.primary.or(d.primary),
        secondary = p.secondary.or(d.secondary),
        onBackground = p.onBackground.or(d.onBackground),
        onSurface = p.onBackground.or(d.onSurface),
        onSurfaceVariant = p.muted.or(d.onSurfaceVariant),
        outline = p.muted.or(d.outline),
        error = p.danger.or(d.error),
        surfaceVariant = p.tile.or(d.surfaceVariant),
    )
}

/** Applies the VictorLaunch fleet palette live (StateFlow), else the built-in neon charcoal theme. */
@Composable
fun VictorSMSTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { FleetThemeClient.start(context) }
    val palette by FleetThemeClient.palette.collectAsState()
    MaterialTheme(colorScheme = schemeFor(palette), content = content)
}
