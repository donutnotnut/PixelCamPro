package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

fun buildExpressivePolygonPath(
    size: Size,
    lobes: Int,
    amplitude: Float,
    rotationDegrees: Float = 0f,
    steps: Int = 44,
): Path {
    val cx = size.width * 0.5f
    val cy = size.height * 0.5f
    val baseRadius = (min(size.width, size.height) * 0.5f) / (1f + amplitude.coerceAtLeast(0f))
    val rotRad = Math.toRadians(rotationDegrees.toDouble())
    val path = Path()

    for (i in 0 until steps) {
        val theta = (2.0 * PI * i) / steps
        val r = baseRadius * (1.0 + amplitude * cos(lobes * theta))
        val angle = theta + rotRad
        val x = (cx + r * cos(angle)).toFloat()
        val y = (cy + r * sin(angle)).toFloat()
        if (i == 0) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }
    }
    path.close()
    return path
}

/**
 * Continuous morphable Material 3 Expressive polar-harmonic shape.
 * Uses hardware-accelerated Outline.Rounded fast-path when resting at zero amplitude.
 */
class ExpressivePolygonShape(
    private val lobes: Int,
    private val amplitude: Float,
    private val rotationDegrees: Float = 0f,
    private val steps: Int = 44,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        if (amplitude <= 0.002f) {
            val r = min(size.width, size.height) * 0.5f
            return Outline.Rounded(
                RoundRect(
                    left = 0f,
                    top = 0f,
                    right = size.width,
                    bottom = size.height,
                    cornerRadius = CornerRadius(r, r),
                )
            )
        }
        return Outline.Generic(
            buildExpressivePolygonPath(size, lobes, amplitude, rotationDegrees, steps)
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExpressivePolygonShape) return false
        return lobes == other.lobes &&
            amplitude == other.amplitude &&
            rotationDegrees == other.rotationDegrees &&
            steps == other.steps
    }

    override fun hashCode(): Int {
        var result = lobes
        result = 31 * result + amplitude.hashCode()
        result = 31 * result + rotationDegrees.hashCode()
        result = 31 * result + steps
        return result
    }
}

object ExpressiveShapes {
    val Clover4 = ExpressivePolygonShape(lobes = 4, amplitude = 0.135f, rotationDegrees = 45f)
    val SoftBurst6 = ExpressivePolygonShape(lobes = 6, amplitude = 0.105f, rotationDegrees = 0f)
    val Cookie8 = ExpressivePolygonShape(lobes = 8, amplitude = 0.088f, rotationDegrees = 22.5f)
    val Cookie9 = ExpressivePolygonShape(lobes = 9, amplitude = 0.085f, rotationDegrees = -10f)
    val Cookie12 = ExpressivePolygonShape(lobes = 12, amplitude = 0.066f, rotationDegrees = 15f)
}

/**
 * Distinct Material 3 Expressive button shape personalities so controls across the app
 * showcase varied geometric forms (Cookie, Clover, Soft Burst, Rounded Square, Circle).
 */
enum class ExpressiveButtonShape {
    CIRCLE_TO_SQUARE,
    CIRCLE_TO_COOKIE,
    CIRCLE_TO_CLOVER,
    CIRCLE_TO_BURST,
    SQUARE_TO_COOKIE,
    COOKIE_PERSISTENT,
}

/**
 * Zero-clipPath hardware-cached Material 3 Expressive background renderer.
 * Draws the exact scalloped Cookie, 4-Leaf Clover, 6-Petal Soft Burst, Rounded Square, or Circle
 * directly via a cached GPU Path fill with ZERO stencil clip masks and ZERO scroll-frame allocations.
 */
fun Modifier.drawExpressiveButtonBackground(
    shapeStyle: ExpressiveButtonShape,
    quantAmp: Float,
    cornerPercent: Int,
    colorProvider: () -> Color,
): Modifier = this.drawWithCache {
    val polyPath = when (shapeStyle) {
        ExpressiveButtonShape.SQUARE_TO_COOKIE ->
            if (quantAmp > 0.008f) buildExpressivePolygonPath(size, 8, quantAmp, 22.5f) else null
        ExpressiveButtonShape.CIRCLE_TO_COOKIE,
        ExpressiveButtonShape.COOKIE_PERSISTENT ->
            if (quantAmp > 0.003f) buildExpressivePolygonPath(size, 8, quantAmp, 22.5f) else null
        ExpressiveButtonShape.CIRCLE_TO_CLOVER ->
            if (quantAmp > 0.003f) buildExpressivePolygonPath(size, 4, quantAmp, 45f) else null
        ExpressiveButtonShape.CIRCLE_TO_BURST ->
            if (quantAmp > 0.003f) buildExpressivePolygonPath(size, 6, quantAmp, 0f) else null
        ExpressiveButtonShape.CIRCLE_TO_SQUARE -> null
    }
    val cornerRadiusPx = if (polyPath == null && cornerPercent < 50) {
        min(size.width, size.height) * (cornerPercent / 100f)
    } else 0f

    onDrawBehind {
        val bg = colorProvider()
        if (bg.alpha > 0.002f) {
            when {
                polyPath != null -> drawPath(path = polyPath, color = bg)
                cornerPercent >= 50 -> drawCircle(color = bg)
                else -> drawRoundRect(
                    color = bg,
                    cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                )
            }
        }
    }
}

/**
 * Zero-clipPath GPU-cached polar-harmonic polygon background fill.
 */
fun Modifier.drawExpressiveShapeBackground(
    lobes: Int,
    amplitude: Float,
    rotationDegrees: Float = 0f,
    colorProvider: () -> Color,
): Modifier = this.drawWithCache {
    val polyPath = if (amplitude > 0.003f) {
        buildExpressivePolygonPath(size, lobes, amplitude, rotationDegrees)
    } else null
    onDrawBehind {
        val bg = colorProvider()
        if (bg.alpha > 0.002f) {
            if (polyPath != null) {
                drawPath(path = polyPath, color = bg)
            } else {
                drawCircle(color = bg)
            }
        }
    }
}

/**
 * Authentic Material 3 Expressive morphing scalloped-cookie LoadingIndicator.
 * Uses GPU-cached Path fill (no stencil clipPath) and only runs while composed.
 */
@Composable
fun ExpressiveLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val infinite = rememberInfiniteTransition(label = "m3_expressive_loader")
    val rotationState = infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "m3_loader_rot",
    )
    val morphAmp by infinite.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "m3_loader_amp",
    )
    val quantAmp = (morphAmp * 80f).toInt() / 80f

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { rotationZ = rotationState.value }
            .drawExpressiveShapeBackground(
                lobes = 9,
                amplitude = quantAmp,
                colorProvider = { color },
            ),
    )
}
