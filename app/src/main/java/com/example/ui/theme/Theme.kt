package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
  primary = RoyalBlue400,
  onPrimary = Navy900,
  secondary = AmberGold400,
  onSecondary = Navy900,
  tertiary = Emerald400,
  background = Color(0xFF0B1120),
  surface = Color(0xFF1E293B),
  onSurface = Color(0xFFF8FAFC),
  surfaceVariant = Color(0xFF334155),
  onSurfaceVariant = Color(0xFFCBD5E1),
  outline = Color(0xFF475569),
  outlineVariant = Color(0xFF334155)
)

private val LightColorScheme = lightColorScheme(
  primary = Navy900,
  onPrimary = Color.White,
  primaryContainer = NavyBlue,
  onPrimaryContainer = Color.White,
  secondary = RoyalBlue600,
  onSecondary = Color.White,
  tertiary = AmberGold500,
  onTertiary = Navy900,
  background = FrostCanvas,
  surface = FrostSurface,
  onSurface = Color(0xFF0F172A),
  surfaceVariant = Color(0xFFF1F5F9),
  onSurfaceVariant = Color(0xFF334155),
  outline = FrostBorder,
  outlineVariant = Color(0xFFE2E8F0)
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false, // Use our bespoke executive banking theme by default
  content: @Composable () -> Unit,
) {
  val colorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
      val context = LocalContext.current
      if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    darkTheme -> DarkColorScheme
    else -> LightColorScheme
  }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    content = content
  )
}

/**
 * High-contrast input field color provider.
 * Ensures text is always crisp, bold black (0xFF0F172A) on light backgrounds,
 * preventing OEM Android skins or custom OS themes from making text invisible.
 */
@Composable
fun kbInputFieldColors(isDark: Boolean = isSystemInDarkTheme()) = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
  focusedTextColor = if (isDark) Color(0xFFFFFFFF) else Color(0xFF0F172A),
  unfocusedTextColor = if (isDark) Color(0xFFF1F5F9) else Color(0xFF0F172A),
  focusedContainerColor = if (isDark) Color(0xFF1E293B) else Color.White,
  unfocusedContainerColor = if (isDark) Color(0xFF0F172A).copy(alpha = 0.75f) else Color.White,
  disabledTextColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
  focusedLabelColor = if (isDark) Color(0xFF38BDF8) else Color(0xFF1E3A8A),
  unfocusedLabelColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF475569),
  focusedBorderColor = if (isDark) Color(0xFF38BDF8) else Color(0xFF1E3A8A),
  unfocusedBorderColor = if (isDark) Color(0xFF475569) else Color(0xFFCBD5E1),
  cursorColor = if (isDark) Color(0xFF38BDF8) else Color(0xFF1E3A8A),
  focusedPlaceholderColor = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8),
  unfocusedPlaceholderColor = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
)
