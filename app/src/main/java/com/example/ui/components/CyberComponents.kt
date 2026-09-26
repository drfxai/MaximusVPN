package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ConnectionStatus
import com.example.ui.theme.AppTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ConnectionButton(
    status: ConnectionStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnected = status == ConnectionStatus.CONNECTED
    val isBusy = status == ConnectionStatus.CONNECTING ||
            status == ConnectionStatus.PREPARING ||
            status == ConnectionStatus.PROXY_CONNECTING ||
            status == ConnectionStatus.VPN_INTERFACE_ESTABLISHED ||
            status == ConnectionStatus.RECONNECTING
    val isFailed = status == ConnectionStatus.FAILED

    val isDarkTheme = AppTheme.colors.isDark
    val borderSubtleColor = AppTheme.colors.borderSubtle
    val borderMediumColor = AppTheme.colors.borderMedium

    // Luxury high-contrast theme-adaptive color palettes
    val targetPrimaryColor = if (isDarkTheme) {
        when {
            isConnected -> Color(0xFF00F59B) // High-luminance mint emerald
            isBusy -> Color(0xFFFFB703) // Radiant solar gold
            isFailed -> Color(0xFFFF4D6D) // Crimson rose
            else -> Color(0xFFD0BCFF) // Royal cyber lavender
        }
    } else {
        when {
            isConnected -> Color(0xFF059669) // Deep saturated emerald (rich contrast on light)
            isBusy -> Color(0xFFD97706) // Rich solar amber
            isFailed -> Color(0xFFDC2626) // Deep crimson
            else -> Color(0xFF6D28D9) // Deep cyber violet
        }
    }
    val animatedPrimaryColor by animateColorAsState(
        targetValue = targetPrimaryColor,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "animatedPrimaryColor"
    )

    val targetSecondaryColor = if (isDarkTheme) {
        when {
            isConnected -> Color(0xFF00E5FF) // Cyber cyan
            isBusy -> Color(0xFFFB8500) // Deep amber neon
            isFailed -> Color(0xFFFF758F) // Soft coral
            else -> Color(0xFFE8DEF8) // Electric soft fuchsia
        }
    } else {
        when {
            isConnected -> Color(0xFF0284C7) // Deep ocean blue/cyan
            isBusy -> Color(0xFFEA580C) // Deep warm orange
            isFailed -> Color(0xFFE11D48) // Rose
            else -> Color(0xFF8B5CF6) // Royal violet
        }
    }
    val animatedSecondaryColor by animateColorAsState(
        targetValue = targetSecondaryColor,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "animatedSecondaryColor"
    )

    val targetAmbientAlpha = when {
        isBusy -> 0.38f
        isConnected -> 0.30f
        isFailed -> 0.22f
        else -> 0.12f
    }
    val animatedAmbientAlpha by animateFloatAsState(
        targetValue = targetAmbientAlpha,
        animationSpec = tween(durationMillis = 500),
        label = "animatedAmbientAlpha"
    )

    // Infinite transitions for smooth, high-FPS fluid orbital kinetics
    val infiniteTransition = rememberInfiniteTransition(label = "cyber_vpn_anim")

    // Dynamic rotation speeds
    val primaryPeriod = if (isBusy) 2000 else if (isConnected) 6500 else 16000
    val primaryRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(primaryPeriod, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "primaryRotation"
    )

    val counterPeriod = if (isBusy) 2800 else if (isConnected) 9500 else 24000
    val counterRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(counterPeriod, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "counterRotation"
    )

    // Tactile breathing scale for the core
    val corePulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isBusy) 1.06f else if (isConnected) 1.028f else 1.008f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isBusy) 700 else 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "corePulseScale"
    )

    // Soft Ambient Halo Breathing
    val ambientBreathScale by infiniteTransition.animateFloat(
        initialValue = 0.90f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isBusy) 850 else 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ambientBreathScale"
    )

    // Dual-Phase Wave Ripples
    val wave1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isBusy) 1400 else 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave1"
    )

    val wave2 by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isBusy) 1400 else 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave2"
    )

    // Dynamic shimmering sweep angle for the core bezel
    val shimmerRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isBusy) 1800 else 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerRotation"
    )

    // Connecting text blink/pulse
    val textPulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "textPulseAlpha"
    )

    Box(
        modifier = modifier.size(264.dp),
        contentAlignment = Alignment.Center
    ) {
        // Multi-Layer Cyber Canvas: Radiant Atmospheric Bloom, Sonar Wavefronts, Chronometer Ticks, Orbiting Laser Flares
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerOffset = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension / 2f

            // 1. Atmospheric Ambient Luminescence Bloom
            val glowRadius = baseRadius * 0.98f * ambientBreathScale
            val glowColors = if (isDarkTheme) {
                listOf(
                    animatedPrimaryColor.copy(alpha = (animatedAmbientAlpha * 1.35f).coerceAtMost(0.50f)),
                    animatedSecondaryColor.copy(alpha = (animatedAmbientAlpha * 0.70f).coerceAtMost(0.28f)),
                    Color.Transparent
                )
            } else {
                listOf(
                    animatedPrimaryColor.copy(alpha = (animatedAmbientAlpha * 0.35f).coerceAtMost(0.14f)),
                    animatedSecondaryColor.copy(alpha = (animatedAmbientAlpha * 0.20f).coerceAtMost(0.08f)),
                    Color.Transparent
                )
            }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = glowColors,
                    center = centerOffset,
                    radius = glowRadius
                ),
                radius = glowRadius,
                center = centerOffset
            )

            // 2. Concentric Sonar Wavefront Shockwaves
            if (isBusy || isConnected) {
                listOf(wave1 % 1f, wave2 % 1f).forEach { progress ->
                    val ringRadius = baseRadius * (0.58f + progress * 0.38f)
                    val ringAlpha = (1f - progress) * (if (isBusy) 0.42f else 0.22f)
                    if (ringAlpha > 0.01f) {
                        drawCircle(
                            color = animatedPrimaryColor.copy(alpha = if (isDarkTheme) ringAlpha else ringAlpha * 1.35f),
                            radius = ringRadius,
                            center = centerOffset,
                            style = Stroke(
                                width = (if (isDarkTheme) 2.2.dp else 2.5.dp).toPx() * (1f - progress * 0.5f)
                            )
                        )
                    }
                }
            }

            // 3. Precision Chrono-Bezel (60 Calibrated Radial Markers)
            val tickCount = 60
            val outerTickRadius = baseRadius * 0.93f
            val innerTickRadius = baseRadius * 0.88f
            val majorTickRadius = baseRadius * 0.83f

            for (i in 0 until tickCount) {
                val angleDeg = (i * 360f / tickCount)
                val angleRad = angleDeg * (PI / 180f)
                val isCardinal = i % 15 == 0 // 0°, 90°, 180°, 270°
                val isFiveMinute = i % 5 == 0

                val startR = if (isCardinal) majorTickRadius else if (isFiveMinute) innerTickRadius - 2.dp.toPx() else innerTickRadius
                val startX = centerOffset.x + (startR * cos(angleRad)).toFloat()
                val startY = centerOffset.y + (startR * sin(angleRad)).toFloat()
                val endX = centerOffset.x + (outerTickRadius * cos(angleRad)).toFloat()
                val endY = centerOffset.y + (outerTickRadius * sin(angleRad)).toFloat()

                // Tick illumination reacts dynamically as the orbiting flux comet sweeps past
                val angleDiff = (primaryRotation - angleDeg + 360f) % 360f
                val isNearBeam = angleDiff < 80f
                val beamGlow = if (isNearBeam) ((80f - angleDiff) / 80f) else 0f

                val tickColor = if (isDarkTheme) {
                    when {
                        isCardinal -> animatedPrimaryColor.copy(alpha = (0.60f + beamGlow * 0.40f).coerceAtMost(1f))
                        isNearBeam && isBusy -> animatedPrimaryColor.copy(alpha = (0.25f + beamGlow * 0.70f).coerceAtMost(1f))
                        isConnected -> animatedSecondaryColor.copy(alpha = if (isFiveMinute) 0.40f else 0.18f)
                        else -> borderSubtleColor.copy(alpha = if (isFiveMinute) 0.35f else 0.12f)
                    }
                } else {
                    when {
                        isCardinal -> animatedPrimaryColor.copy(alpha = (0.85f + beamGlow * 0.15f).coerceAtMost(1f))
                        isNearBeam && isBusy -> animatedPrimaryColor.copy(alpha = (0.60f + beamGlow * 0.40f).coerceAtMost(1f))
                        isConnected -> if (isFiveMinute) animatedSecondaryColor.copy(alpha = 0.75f) else animatedSecondaryColor.copy(alpha = 0.38f)
                        else -> if (isFiveMinute) borderMediumColor.copy(alpha = 0.85f) else borderMediumColor.copy(alpha = 0.45f)
                    }
                }

                drawLine(
                    color = tickColor,
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = if (isCardinal) (if (isDarkTheme) 2.2.dp else 2.6.dp).toPx() else if (isFiveMinute) (if (isDarkTheme) 1.5.dp else 1.8.dp).toPx() else (if (isDarkTheme) 1.dp else 1.2.dp).toPx(),
                    cap = StrokeCap.Round
                )

                // Glowing cardinal jewel pips
                if (isCardinal) {
                    val pipDist = outerTickRadius + 4.dp.toPx()
                    val pipX = centerOffset.x + (pipDist * cos(angleRad)).toFloat()
                    val pipY = centerOffset.y + (pipDist * sin(angleRad)).toFloat()
                    drawCircle(
                        color = if (isBusy || isConnected) animatedPrimaryColor else borderMediumColor,
                        radius = (if (isBusy) 2.6f else 2.0f).dp.toPx(),
                        center = Offset(pipX, pipY)
                    )
                }
            }

            // 4. Primary Clockwise Orbiting Neon Flux Comet (Outer Ring)
            val fluxRadius = baseRadius * 0.78f
            val fluxRect = Size(fluxRadius * 2, fluxRadius * 2)
            val fluxTopLeft = Offset(centerOffset.x - fluxRadius, centerOffset.y - fluxRadius)

            // Orbital guide track
            drawCircle(
                color = if (isDarkTheme) {
                    if (isConnected || isBusy) animatedPrimaryColor.copy(alpha = 0.18f) else borderSubtleColor.copy(alpha = 0.22f)
                } else {
                    if (isConnected || isBusy) animatedPrimaryColor.copy(alpha = 0.38f) else borderMediumColor.copy(alpha = 0.65f)
                },
                radius = fluxRadius,
                center = centerOffset,
                style = Stroke(
                    width = (if (isDarkTheme) 1.dp else 1.3.dp).toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx()))
                )
            )

            // Sweeping comet arc with gradient tail
            val sweepAngleSpan = if (isBusy) 125f else if (isConnected) 90f else 55f
            rotate(degrees = primaryRotation, pivot = centerOffset) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            animatedSecondaryColor.copy(alpha = if (isDarkTheme) 0.25f else 0.40f),
                            animatedPrimaryColor.copy(alpha = if (isDarkTheme) 0.85f else 0.92f),
                            animatedPrimaryColor
                        ),
                        center = centerOffset
                    ),
                    startAngle = 0f,
                    sweepAngle = sweepAngleSpan,
                    useCenter = false,
                    topLeft = fluxTopLeft,
                    size = fluxRect,
                    style = Stroke(
                        width = if (isBusy) (if (isDarkTheme) 3.6.dp else 4.0.dp).toPx() else (if (isDarkTheme) 2.6.dp else 3.2.dp).toPx(),
                        cap = StrokeCap.Round
                    )
                )

                // High-luminance beacon spark with double glow halo at head
                val headAngleRad = sweepAngleSpan * (PI / 180f)
                val beaconX = centerOffset.x + (fluxRadius * cos(headAngleRad)).toFloat()
                val beaconY = centerOffset.y + (fluxRadius * sin(headAngleRad)).toFloat()

                drawCircle(
                    color = animatedPrimaryColor.copy(alpha = if (isDarkTheme) 0.65f else 0.35f),
                    radius = (if (isBusy) 8.5f else 6.5f).dp.toPx(),
                    center = Offset(beaconX, beaconY)
                )
                drawCircle(
                    color = animatedPrimaryColor,
                    radius = (if (isBusy) 4.5f else 3.4f).dp.toPx(),
                    center = Offset(beaconX, beaconY)
                )
                drawCircle(
                    color = Color.White,
                    radius = (if (isBusy) 2.6f else 1.8f).dp.toPx(),
                    center = Offset(beaconX, beaconY)
                )
            }

            // 5. Counter-Orbiting Laser Brackets (Inner Ring)
            val innerLaserRadius = baseRadius * 0.69f
            val innerLaserRect = Size(innerLaserRadius * 2, innerLaserRadius * 2)
            val innerLaserTopLeft = Offset(centerOffset.x - innerLaserRadius, centerOffset.y - innerLaserRadius)

            rotate(degrees = counterRotation, pivot = centerOffset) {
                listOf(0f, 180f).forEach { bracketStart ->
                    drawArc(
                        color = if (isDarkTheme) {
                            if (isBusy) animatedSecondaryColor.copy(alpha = 0.85f)
                            else if (isConnected) animatedSecondaryColor.copy(alpha = 0.50f)
                            else borderMediumColor.copy(alpha = 0.35f)
                        } else {
                            if (isBusy) animatedSecondaryColor.copy(alpha = 0.90f)
                            else if (isConnected) animatedSecondaryColor.copy(alpha = 0.75f)
                            else borderMediumColor.copy(alpha = 0.75f)
                        },
                        startAngle = bracketStart,
                        sweepAngle = if (isBusy) 48f else 32f,
                        useCenter = false,
                        topLeft = innerLaserTopLeft,
                        size = innerLaserRect,
                        style = Stroke(width = (if (isDarkTheme) 1.8.dp else 2.2.dp).toPx(), cap = StrokeCap.Round)
                    )
                }
            }
        }

        // Central Tactile Frosted-Glass Core Button with Specular Metallic Bezel
        Box(
            modifier = Modifier
                .size(144.dp)
                .scale(corePulseScale)
                .shadow(
                    elevation = if (isBusy || isConnected) 16.dp else if (isDarkTheme) 6.dp else 10.dp,
                    shape = CircleShape,
                    ambientColor = animatedPrimaryColor.copy(alpha = if (isDarkTheme) (if (isBusy || isConnected) 0.55f else 0.18f) else 0.28f),
                    spotColor = animatedPrimaryColor.copy(alpha = if (isDarkTheme) (if (isBusy || isConnected) 0.55f else 0.18f) else 0.32f)
                )
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = if (isDarkTheme) {
                            if (isBusy) {
                                listOf(Color(0xFF2E1C28), Color(0xFF191420), Color(0xFF0C0D14))
                            } else if (isConnected) {
                                listOf(Color(0xFF102D24), Color(0xFF0D1D1B), Color(0xFF080C10))
                            } else if (isFailed) {
                                listOf(Color(0xFF33141E), Color(0xFF1F0D14), Color(0xFF0C0D14))
                            } else {
                                listOf(Color(0xFF222332), Color(0xFF151622), Color(0xFF0C0D14))
                            }
                        } else {
                            if (isBusy) {
                                listOf(Color(0xFFFFFFFF), Color(0xFFFFFBEB), Color(0xFFFEF3C7))
                            } else if (isConnected) {
                                listOf(Color(0xFFFFFFFF), Color(0xFFECFDF5), Color(0xFFD1FAE5))
                            } else if (isFailed) {
                                listOf(Color(0xFFFFFFFF), Color(0xFFFEF2F2), Color(0xFFFEE2E2))
                            } else {
                                listOf(Color(0xFFFFFFFF), Color(0xFFF8FAFC), Color(0xFFEEF2F6))
                            }
                        }
                    )
                )
                .border(
                    width = if (isBusy || isConnected) (if (isDarkTheme) 2.2.dp else 2.6.dp) else (if (isDarkTheme) 1.5.dp else 1.8.dp),
                    brush = Brush.sweepGradient(
                        if (isDarkTheme) {
                            listOf(
                                animatedPrimaryColor.copy(alpha = 0.95f),
                                animatedSecondaryColor.copy(alpha = 0.55f),
                                animatedPrimaryColor.copy(alpha = 0.15f),
                                animatedSecondaryColor.copy(alpha = 0.85f),
                                animatedPrimaryColor.copy(alpha = 0.95f)
                            )
                        } else {
                            listOf(
                                animatedPrimaryColor.copy(alpha = 0.95f),
                                animatedSecondaryColor.copy(alpha = 0.75f),
                                animatedPrimaryColor.copy(alpha = 0.35f),
                                animatedSecondaryColor.copy(alpha = 0.85f),
                                animatedPrimaryColor.copy(alpha = 0.95f)
                            )
                        }
                    ),
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true, color = animatedPrimaryColor),
                    onClick = onClick
                )
                .testTag("vpn_connect_button"),
            contentAlignment = Alignment.Center
        ) {
            // Subtle internal glass gloss reflection arc
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawArc(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (isDarkTheme) 0.18f else 0.55f),
                            Color.Transparent
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height / 2f)
                    ),
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(4.dp.toPx(), 4.dp.toPx()),
                    size = Size(size.width - 8.dp.toPx(), size.height - 8.dp.toPx()),
                    style = Stroke(width = 1.2.dp.toPx())
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(48.dp)
                ) {
                    // Glowing circular aura behind icon when active
                    if (isBusy || isConnected) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawCircle(
                                color = animatedPrimaryColor.copy(alpha = if (isDarkTheme) (if (isBusy) 0.20f else 0.15f) else (if (isBusy) 0.14f else 0.10f)),
                                radius = size.minDimension / 2.2f
                            )
                        }
                    }
                    Icon(
                        imageVector = if (isConnected) Icons.Default.Security else Icons.Default.PowerSettingsNew,
                        contentDescription = "VPN Toggle",
                        tint = animatedPrimaryColor,
                        modifier = Modifier.size(38.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = when (status) {
                        ConnectionStatus.CONNECTED -> "CONNECTED"
                        ConnectionStatus.CONNECTING -> "CONNECTING"
                        ConnectionStatus.VPN_INTERFACE_ESTABLISHED -> "TUNNEL ON"
                        ConnectionStatus.PROXY_CONNECTING -> "HANDSHAKE"
                        ConnectionStatus.PREPARING -> "STARTING"
                        ConnectionStatus.RECONNECTING -> "RECONNECT"
                        ConnectionStatus.DISCONNECTING -> "DISCONNECT"
                        ConnectionStatus.FAILED -> "RETRY"
                        ConnectionStatus.DISCONNECTED -> "CONNECT"
                    },
                    color = animatedPrimaryColor.copy(
                        alpha = if (isBusy) textPulseAlpha else 1f
                    ),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = 1.4.sp
                )
            }
        }
    }
}

@Composable
fun StatusBadge(
    status: ConnectionStatus,
    modifier: Modifier = Modifier
) {
    val (dotColor, text) = when (status) {
        ConnectionStatus.CONNECTED -> Pair(AppTheme.colors.statusConnected, "CONNECTED")
        ConnectionStatus.VPN_INTERFACE_ESTABLISHED -> Pair(AppTheme.colors.statusWarning, "VPN ESTABLISHED...")
        ConnectionStatus.PROXY_CONNECTING -> Pair(AppTheme.colors.statusWarning, "CONNECTING PROXY...")
        ConnectionStatus.CONNECTING, ConnectionStatus.PREPARING -> Pair(AppTheme.colors.statusWarning, "CONNECTING...")
        ConnectionStatus.RECONNECTING -> Pair(AppTheme.colors.statusWarning, "RECONNECTING...")
        ConnectionStatus.DISCONNECTING -> Pair(AppTheme.colors.textMuted, "DISCONNECTING...")
        ConnectionStatus.FAILED -> Pair(AppTheme.colors.statusError, "FAILED")
        ConnectionStatus.DISCONNECTED -> Pair(AppTheme.colors.textMuted, "DISCONNECTED")
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .background(AppTheme.colors.surfaceCard)
            .border(1.dp, AppTheme.colors.borderSubtle, RoundedCornerShape(100.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            color = AppTheme.colors.textPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
fun LatencyPill(
    latencyMs: Long?,
    modifier: Modifier = Modifier
) {
    if (latencyMs == null) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(AppTheme.colors.surfaceElevated)
                .border(1.dp, AppTheme.colors.borderSubtle, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("--- ms", color = AppTheme.colors.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
        return
    }

    val (color, label) = when {
        latencyMs < 120 -> Pair(AppTheme.colors.statusConnected, "${latencyMs}ms")
        latencyMs < 300 -> Pair(AppTheme.colors.statusWarning, "${latencyMs}ms")
        else -> Pair(AppTheme.colors.statusError, "${latencyMs}ms")
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(AppTheme.colors.surfaceElevated)
            .border(1.dp, AppTheme.colors.borderSubtle, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, color = AppTheme.colors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
