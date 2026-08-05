package org.skyphusion.prism.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFFE94560)
private val DarkBg = Color(0xFF1A1A2E)
private val DarkSurface = Color(0xFF16213E)

private val DarkColors =
  darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = DarkBg,
    surface = DarkSurface,
    onBackground = Color(0xFFEAEAEA),
    onSurface = Color(0xFFEAEAEA),
  )

private val LightColors =
  lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
  )

@Composable
fun PrismTheme(content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  MaterialTheme(
    colorScheme = if (dark) DarkColors else LightColors,
    content = content,
  )
}
