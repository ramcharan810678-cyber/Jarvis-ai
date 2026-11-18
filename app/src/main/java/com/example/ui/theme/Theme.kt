package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class JarvisExtendedColors(
    val holoTeal: Color = HoloTeal,
    val gridLine: Color = HudGridLine,
    val surfaceGlass: Color = HudSurfaceGlass,
    val amberAlert: Color = AmberAlert,
    val emeraldActive: Color = EmeraldActive,
    val arcCyanDim: Color = ArcCyanDim
)

val LocalJarvisColors = staticCompositionLocalOf { JarvisExtendedColors() }

private val JarvisColorScheme = darkColorScheme(
    primary = ArcCyan,
    onPrimary = DeepSpaceObsidian,
    primaryContainer = HudSurfaceElevated,
    onPrimaryContainer = IceWhite,
    secondary = ElectricBlue,
    onSecondary = IceWhite,
    secondaryContainer = HudSurface,
    onSecondaryContainer = IceWhite,
    tertiary = HoloTeal,
    onTertiary = DeepSpaceObsidian,
    background = DeepSpaceObsidian,
    onBackground = IceWhite,
    surface = HudSurface,
    onSurface = IceWhite,
    surfaceVariant = HudSurfaceElevated,
    onSurfaceVariant = MutedSteel,
    outline = HudGridLine,
    outlineVariant = ArcCyanDim,
    error = CrimsonError,
    onError = IceWhite,
    errorContainer = CrimsonSurface,
    onErrorContainer = IceWhite
)

private val JarvisShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp)
)

@Composable
fun JarvisTheme(
    content: @Composable () -> Unit
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalJarvisColors provides JarvisExtendedColors()
    ) {
        MaterialTheme(
            colorScheme = JarvisColorScheme,
            typography = JarvisTypography,
            shapes = JarvisShapes,
            content = content
        )
    }
}
