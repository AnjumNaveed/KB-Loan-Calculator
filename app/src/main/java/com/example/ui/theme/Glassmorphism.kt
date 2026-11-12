package com.example.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object GlassTheme {
  // Deep Executive Banking Palette
  val DeepNavy = Color(0xFF0B132B)
  val NavyHeader = Color(0xFF0F172A)
  val FrostedSlate = Color(0xFF1E293B)
  val SoftSlate = Color(0xFF334155)
  val SlateMuted = Color(0xFF64748B)
  val SlateLight = Color(0xFFF1F5F9)

  // Accents & Financial Signifiers
  val GoldAccent = Color(0xFFF59E0B)
  val GoldGlow = Color(0xFFFCD34D)
  val GoldWarm = Color(0xFFD97706)
  val EmeraldAccent = Color(0xFF10B981)
  val EmeraldLight = Color(0xFF34D399)
  val EmeraldDark = Color(0xFF047857)
  val EmeraldBg = Color(0xFFECFDF5)
  val BlueAccent = Color(0xFF2563EB)
  val BlueLight = Color(0xFF60A5FA)
  val BlueDark = Color(0xFF1D4ED8)
  val BlueBg = Color(0xFFEFF6FF)
  val CrimsonAccent = Color(0xFFEF4444)
  val CrimsonDark = Color(0xFFDC2626)
  val CrimsonBg = Color(0xFFFEF2F2)

  // Translucent Glass Surfaces
  val CardGlassWhite = Color(0xFFFFFFFF).copy(alpha = 0.94f)
  val CardGlassDark = Color(0xFF0F172A).copy(alpha = 0.92f)
  val CardGlassSlate = Color(0xFF1E293B).copy(alpha = 0.90f)
  val GlassChipBg = Color(0xFFF1F5F9).copy(alpha = 0.85f)

  // Specular Borders (giving glass-edge light refraction)
  val BorderHighlight = Color.White.copy(alpha = 0.85f)
  val BorderShadow = Color(0xFF94A3B8).copy(alpha = 0.28f)
  val BorderDarkGlass = Color.White.copy(alpha = 0.18f)

  // Specular Gradient Border Brush
  val SpecularBorderBrush = Brush.linearGradient(
    colors = listOf(
      Color.White.copy(alpha = 0.90f),
      Color(0xFFCBD5E1).copy(alpha = 0.35f)
    )
  )

  val DarkSpecularBorderBrush = Brush.linearGradient(
    colors = listOf(
      Color.White.copy(alpha = 0.28f),
      Color(0xFF334155).copy(alpha = 0.40f)
    )
  )

  // Background Mesh & Header Brushes
  val CanvasMeshBrush = Brush.verticalGradient(
    colors = listOf(
      Color(0xFFF8FAFC),
      Color(0xFFF1F5F9),
      Color(0xFFE2E8F0)
    )
  )

  val HeaderMeshBrush = Brush.horizontalGradient(
    colors = listOf(
      Color(0xFF0F172A),
      Color(0xFF1E293B),
      Color(0xFF0F172A)
    )
  )

  val GoldGlassBrush = Brush.linearGradient(
    colors = listOf(
      Color(0xFFFBBF24),
      Color(0xFFF59E0B)
    )
  )

  val BlueGlassBrush = Brush.linearGradient(
    colors = listOf(
      Color(0xFF3B82F6),
      Color(0xFF1D4ED8)
    )
  )

  val EmeraldGlassBrush = Brush.linearGradient(
    colors = listOf(
      Color(0xFF10B981),
      Color(0xFF047857)
    )
  )

  val CrimsonGlassBrush = Brush.linearGradient(
    colors = listOf(
      Color(0xFFF87171),
      Color(0xFFDC2626)
    )
  )
}

/**
 * 3D spring press effect that compresses subtly in 3D perspective when pressed and bounces back smoothly.
 */
fun Modifier.press3d(
  scaleDown: Float = 0.965f
): Modifier = composed {
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()
  val scale by animateFloatAsState(
    targetValue = if (isPressed) scaleDown else 1f,
    animationSpec = spring(
      dampingRatio = Spring.DampingRatioMediumBouncy,
      stiffness = Spring.StiffnessLow
    ),
    label = "press3d"
  )
  this.graphicsLayer {
    scaleX = scale
    scaleY = scale
  }
}

/**
 * Premium Frosted Glass Card with subtle top specular highlight, dual-tone edge border,
 * and soft multi-layered 3D shadow.
 */
@Composable
fun GlassCard(
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(16.dp),
  backgroundColor: Color = Color.Unspecified,
  borderColor: Color = Color.Unspecified,
  elevation: Dp = 4.dp,
  onClick: (() -> Unit)? = null,
  contentPadding: PaddingValues = PaddingValues(14.dp),
  content: @Composable ColumnScope.() -> Unit
) {
  val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
  val resolvedBg = if (backgroundColor != Color.Unspecified) backgroundColor else {
    if (isDark) Color(0xFF1E293B).copy(alpha = 0.94f) else GlassTheme.CardGlassWhite
  }
  val resolvedBorder = if (borderColor != Color.Unspecified) borderColor else {
    if (isDark) Color(0xFF334155) else GlassTheme.BorderHighlight
  }

  val cardModifier = if (onClick != null) {
    modifier
      .press3d(scaleDown = 0.97f)
      .shadow(
        elevation = elevation,
        shape = shape,
        ambientColor = if (isDark) Color(0x33000000) else Color(0x140F172A),
        spotColor = if (isDark) Color(0x4D000000) else Color(0x220F172A)
      )
      .clip(shape)
      .background(resolvedBg)
      .clickable(onClick = onClick)
  } else {
    modifier
      .shadow(
        elevation = elevation,
        shape = shape,
        ambientColor = if (isDark) Color(0x33000000) else Color(0x140F172A),
        spotColor = if (isDark) Color(0x4D000000) else Color(0x220F172A)
      )
      .clip(shape)
      .background(resolvedBg)
  }

  Surface(
    modifier = cardModifier,
    shape = shape,
    color = Color.Transparent,
    contentColor = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A),
    border = BorderStroke(
      1.dp,
      Brush.linearGradient(
        listOf(
          resolvedBorder,
          resolvedBorder.copy(alpha = if (isDark) 0.35f else 0.25f)
        )
      )
    )
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(contentPadding),
      content = content
    )
  }
}

/**
 * Dark-mode variant of the GlassCard for high-contrast executive sections.
 */
@Composable
fun DarkGlassCard(
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(16.dp),
  elevation: Dp = 6.dp,
  onClick: (() -> Unit)? = null,
  contentPadding: PaddingValues = PaddingValues(14.dp),
  content: @Composable ColumnScope.() -> Unit
) {
  GlassCard(
    modifier = modifier,
    shape = shape,
    backgroundColor = GlassTheme.CardGlassDark,
    borderColor = Color.White.copy(alpha = 0.25f),
    elevation = elevation,
    onClick = onClick,
    contentPadding = contentPadding,
    content = content
  )
}

/**
 * Fullscreen ambient canvas mesh that renders subtle depth gradients behind content
 * so that frosted glass cards pop with genuine 3D translucent depth.
 * 100% pure native Kotlin graphics - 0 external web/css dependencies.
 */
@Composable
fun GlassMeshBackground(
  modifier: Modifier = Modifier,
  content: @Composable BoxScope.() -> Unit
) {
  val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
  val baseColors = if (isDark) {
    listOf(
      Color(0xFF0F172A),
      Color(0xFF0B1120),
      Color(0xFF020617)
    )
  } else {
    listOf(
      Color(0xFFF8FAFC),
      Color(0xFFF1F5F9),
      Color(0xFFE2E8F0)
    )
  }
  val glowSapphire = if (isDark) Color(0xFF3B82F6).copy(alpha = 0.12f) else Color(0xFF3B82F6).copy(alpha = 0.08f)
  val glowAmber = if (isDark) Color(0xFFF59E0B).copy(alpha = 0.09f) else Color(0xFFF59E0B).copy(alpha = 0.06f)

  Box(
    modifier = modifier
      .fillMaxSize()
      .drawBehind {
        // Base metallic gradient
        drawRect(
          brush = Brush.verticalGradient(
            colors = baseColors
          )
        )
        // Ambient soft top-right sapphire glow
        drawCircle(
          brush = Brush.radialGradient(
            colors = listOf(
              glowSapphire,
              Color.Transparent
            ),
            center = Offset(size.width * 0.85f, size.height * 0.12f),
            radius = size.width * 0.65f
          )
        )
        // Ambient soft bottom-left gold/emerald glow
        drawCircle(
          brush = Brush.radialGradient(
            colors = listOf(
              glowAmber,
              Color.Transparent
            ),
            center = Offset(size.width * 0.15f, size.height * 0.75f),
            radius = size.width * 0.70f
          )
        )
      },
    content = content
  )
}

/**
 * Tactile 3D Glass Action Button with specular highlight and spring bounce
 */
@Composable
fun Glass3DButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  brush: Brush = GlassTheme.BlueGlassBrush,
  icon: (@Composable () -> Unit)? = null,
  enabled: Boolean = true,
  height: Dp = 46.dp
) {
  Surface(
    modifier = modifier
      .height(height)
      .press3d(scaleDown = 0.96f)
      .shadow(
        elevation = if (enabled) 4.dp else 0.dp,
        shape = RoundedCornerShape(12.dp),
        ambientColor = Color(0x1F0F172A),
        spotColor = Color(0x330F172A)
      )
      .clip(RoundedCornerShape(12.dp))
      .clickable(enabled = enabled, onClick = onClick),
    shape = RoundedCornerShape(12.dp),
    color = Color.Transparent,
    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f))
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(brush)
        .padding(horizontal = 14.dp, vertical = 8.dp),
      contentAlignment = Alignment.Center
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
      ) {
        if (icon != null) {
          icon()
          Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
          text = text,
          fontSize = 12.5.sp,
          fontWeight = FontWeight.Bold,
          color = Color.White
        )
      }
    }
  }
}
