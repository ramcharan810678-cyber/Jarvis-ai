package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.HologramActivityMode
import com.example.ui.theme.LocalJarvisColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun JarvisHologramCore(
    mode: HologramActivityMode,
    voiceRmsLevel: Float,
    coreSize: Dp,
    showStatusReadout: Boolean,
    modifier: Modifier = Modifier
) {
    val jarvisColors = LocalJarvisColors.current
    val cdText = stringResource(R.string.cd_hologram_core)

    val primaryGlow by animateColorAsState(
        targetValue = when (mode) {
            HologramActivityMode.IDLE -> MaterialTheme.colorScheme.primary
            HologramActivityMode.RECORDING -> jarvisColors.emeraldActive
            HologramActivityMode.TRANSCRIBING -> jarvisColors.amberAlert
            HologramActivityMode.PROCESSING -> jarvisColors.amberAlert
            HologramActivityMode.THINKING -> MaterialTheme.colorScheme.secondary
            HologramActivityMode.SPEAKING -> jarvisColors.holoTeal
            HologramActivityMode.ALERT -> MaterialTheme.colorScheme.error
        },
        animationSpec = tween(durationMillis = 320),
        label = "hologram_primary_glow"
    )

    val secondaryGlow by animateColorAsState(
        targetValue = when (mode) {
            HologramActivityMode.ALERT -> jarvisColors.amberAlert
            HologramActivityMode.RECORDING -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.secondary
        },
        animationSpec = tween(durationMillis = 320),
        label = "hologram_secondary_glow"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "hologram_orbits")
    val slowAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "slow_orbit"
    )
    val counterAngle by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "counter_orbit"
    )
    val fastAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "fast_orbit"
    )
    val breathePulse by infiniteTransition.animateFloat(
        initialValue = 0.86f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe_pulse"
    )
    val speechWavePulse by infiniteTransition.animateFloat(
        initialValue = 0.78f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 420, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "speech_wave_pulse"
    )

    val animatedRms by animateFloatAsState(
        targetValue = voiceRmsLevel.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = 500f),
        label = "voice_rms_spring"
    )

    val activeOrbitAngle = when (mode) {
        HologramActivityMode.TRANSCRIBING,
        HologramActivityMode.PROCESSING,
        HologramActivityMode.THINKING -> fastAngle
        else -> slowAngle
    }

    val corePulseScale = when (mode) {
        HologramActivityMode.RECORDING -> 0.92f + (animatedRms * 0.28f)
        HologramActivityMode.SPEAKING -> speechWavePulse
        HologramActivityMode.TRANSCRIBING,
        HologramActivityMode.PROCESSING,
        HologramActivityMode.THINKING -> 0.95f + (breathePulse - 0.86f) * 0.6f
        else -> breathePulse
    }

    val statusText = when (mode) {
        HologramActivityMode.IDLE -> stringResource(R.string.status_idle)
        HologramActivityMode.RECORDING -> stringResource(R.string.status_recording)
        HologramActivityMode.TRANSCRIBING -> stringResource(R.string.status_transcribing)
        HologramActivityMode.PROCESSING -> stringResource(R.string.status_processing)
        HologramActivityMode.THINKING -> stringResource(R.string.status_thinking)
        HologramActivityMode.SPEAKING -> stringResource(R.string.status_speaking)
        HologramActivityMode.ALERT -> stringResource(R.string.status_error)
    }

    Column(
        modifier = modifier
            .testTag("jarvis_hologram_core")
            .semantics { contentDescription = cdText },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(coreSize)
                .aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxRadius = size.minDimension / 2f

                // Atmospheric radial hologram field
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryGlow.copy(alpha = 0.28f),
                            secondaryGlow.copy(alpha = 0.10f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = maxRadius
                    ),
                    radius = maxRadius,
                    center = center
                )

                // Outer segmented telemetry ring
                val outerRadius = maxRadius * 0.90f
                val outerArcSize = Size(outerRadius * 2f, outerRadius * 2f)
                val outerTopLeft = Offset(center.x - outerRadius, center.y - outerRadius)
                rotate(degrees = activeOrbitAngle, pivot = center) {
                    val segmentCount = 4
                    for (i in 0 until segmentCount) {
                        drawArc(
                            color = primaryGlow.copy(alpha = 0.72f),
                            startAngle = i * 90f + 10f,
                            sweepAngle = 56f,
                            useCenter = false,
                            topLeft = outerTopLeft,
                            size = outerArcSize,
                            style = Stroke(width = maxRadius * 0.032f, cap = StrokeCap.Round)
                        )
                    }
                }

                // 24 radial HUD tick marks
                val tickInner = maxRadius * 0.74f
                val tickOuter = maxRadius * 0.80f
                for (i in 0 until 24) {
                    val angleRad = (i * 15.0) * PI / 180.0
                    val isMajor = i % 3 == 0
                    val start = Offset(
                        center.x + (tickInner * cos(angleRad)).toFloat(),
                        center.y + (tickInner * sin(angleRad)).toFloat()
                    )
                    val end = Offset(
                        center.x + ((if (isMajor) tickOuter * 1.04f else tickOuter) * cos(angleRad)).toFloat(),
                        center.y + ((if (isMajor) tickOuter * 1.04f else tickOuter) * sin(angleRad)).toFloat()
                    )
                    drawLine(
                        color = primaryGlow.copy(alpha = if (isMajor) 0.75f else 0.28f),
                        start = start,
                        end = end,
                        strokeWidth = if (isMajor) maxRadius * 0.022f else maxRadius * 0.012f,
                        cap = StrokeCap.Round
                    )
                }

                // Counter-rotating mid orbital arc ring
                val midRadius = maxRadius * 0.64f
                val midArcSize = Size(midRadius * 2f, midRadius * 2f)
                val midTopLeft = Offset(center.x - midRadius, center.y - midRadius)
                rotate(degrees = counterAngle, pivot = center) {
                    for (i in 0 until 3) {
                        drawArc(
                            color = secondaryGlow.copy(alpha = 0.82f),
                            startAngle = i * 120f + 14f,
                            sweepAngle = 78f,
                            useCenter = false,
                            topLeft = midTopLeft,
                            size = midArcSize,
                            style = Stroke(width = maxRadius * 0.042f, cap = StrokeCap.Butt)
                        )
                    }
                }

                // Reactive inner waveform halo
                val haloRadius = (maxRadius * 0.45f) * corePulseScale.coerceIn(0.75f, 1.26f)
                drawCircle(
                    color = primaryGlow.copy(alpha = 0.35f),
                    radius = haloRadius,
                    center = center,
                    style = Stroke(width = maxRadius * 0.024f)
                )

                // Inner luminous core orb
                val coreRadius = maxRadius * 0.32f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.90f),
                            primaryGlow.copy(alpha = 0.80f),
                            primaryGlow.copy(alpha = 0.12f)
                        ),
                        center = center,
                        radius = coreRadius
                    ),
                    radius = coreRadius,
                    center = center
                )

                // Geometric Arc-Reactor inner triangle reticle
                val triRadius = maxRadius * 0.24f
                rotate(degrees = activeOrbitAngle * 0.35f, pivot = center) {
                    val path = Path().apply {
                        for (i in 0 until 3) {
                            val theta = (-90.0 + i * 120.0) * PI / 180.0
                            val x = center.x + (triRadius * cos(theta)).toFloat()
                            val y = center.y + (triRadius * sin(theta)).toFloat()
                            if (i == 0) moveTo(x, y) else lineTo(x, y)
                        }
                        close()
                    }
                    drawPath(
                        path = path,
                        color = Color.White.copy(alpha = 0.88f),
                        style = Stroke(width = maxRadius * 0.022f)
                    )
                }
            }
        }

        if (showStatusReadout) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelLarge,
                color = primaryGlow,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}
