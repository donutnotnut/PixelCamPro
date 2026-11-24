package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.camera.hardware.HorizonOrientation
import com.example.camera.model.DetectedFace
import com.example.camera.model.GridOverlayType
import com.example.camera.model.HdrMode
import com.example.camera.model.LiveTelemetry
import com.example.camera.model.ManualExposureSettings
import com.example.camera.processing.LiveFrameAnalysisResult
import com.example.ui.theme.FocusGreen
import com.example.ui.theme.PixelAmber
import com.example.ui.theme.ProTeal
import com.example.ui.viewmodel.FocusReticleState
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun CompositionGridOverlay(
    gridType: GridOverlayType,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = gridType != GridOverlayType.OFF,
        enter = fadeIn(M3ExpressiveMotion.fastEffects()),
        exit = fadeOut(M3ExpressiveMotion.fastEffects()),
        modifier = modifier.fillMaxSize(),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Isolate static grid lines on a hardware RenderNode without full-screen offscreen FBO allocation
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        ) {
            val lineColor = Color.White.copy(alpha = 0.24f)
            val stroke = 1.dp.toPx()

            val fractions = when (gridType) {
                GridOverlayType.RULE_OF_THIRDS -> listOf(1f / 3f, 2f / 3f)
                GridOverlayType.GOLDEN_RATIO -> listOf(0.382f, 0.618f)
                GridOverlayType.SQUARE_4X4 -> listOf(0.25f, 0.5f, 0.75f)
                GridOverlayType.OFF -> emptyList()
            }

            fractions.forEach { f ->
                val x = size.width * f
                val y = size.height * f
                drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
            }
        }
    }
}

@Composable
fun HorizonLevelOverlay(
    horizonFlow: StateFlow<HorizonOrientation>,
    modifier: Modifier = Modifier,
) {
    val horizonState = horizonFlow.collectAsStateWithLifecycle()
    val isAligned = horizonState.value.isLevelAligned
    val rollDegrees = horizonState.value.rollDegrees.coerceIn(-45f, 45f)

    val wingColorState = animateColorAsState(
        targetValue = if (isAligned) FocusGreen else Color.White.copy(alpha = 0.78f),
        animationSpec = M3ExpressiveMotion.fastEffects(),
        label = "horizon_color",
    )
    val alignScaleState = animateFloatAsState(
        targetValue = if (isAligned) 1.07f else 1f,
        animationSpec = M3ExpressiveMotion.fastSpatial(),
        label = "horizon_scale",
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // Static reference ticks cached in their own hardware RenderNode (never redrawn as phone tilts)
        Canvas(
            modifier = Modifier
                .size(144.dp)
                .graphicsLayer {
                    val s = alignScaleState.value
                    scaleX = s
                    scaleY = s
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val refColor = Color.White.copy(alpha = 0.35f)
            val tickStroke = 1.6.dp.toPx()

            drawLine(
                color = refColor,
                start = Offset(center.x - 56.dp.toPx(), center.y),
                end = Offset(center.x - 36.dp.toPx(), center.y),
                strokeWidth = tickStroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = refColor,
                start = Offset(center.x + 36.dp.toPx(), center.y),
                end = Offset(center.x + 56.dp.toPx(), center.y),
                strokeWidth = tickStroke,
                cap = StrokeCap.Round,
            )
        }

        // Rotating level bar transformed purely via hardware GPU matrix (rotationZ) with zero path re-recording
        Canvas(
            modifier = Modifier
                .size(144.dp)
                .graphicsLayer {
                    rotationZ = -horizonState.value.rollDegrees.coerceIn(-45f, 45f)
                    val s = alignScaleState.value
                    scaleX = s
                    scaleY = s
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val stroke = if (isAligned) 2.5.dp.toPx() else 1.6.dp.toPx()
            drawLine(
                color = wingColorState.value,
                start = Offset(center.x - 30.dp.toPx(), center.y),
                end = Offset(center.x + 30.dp.toPx(), center.y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier.offset(y = 22.dp),
        ) {
            Text(
                text = String.format("%.1f°", abs(rollDegrees)),
                style = MaterialTheme.typography.labelSmall,
                color = wingColorState.value,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
fun FocusReticleOverlay(
    reticle: FocusReticleState?,
    evValue: Float,
    onEvDragStart: () -> Unit,
    onEvChange: (Float) -> Unit,
    onEvDragEnd: () -> Unit,
    onUnlockAeAf: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberCameraHaptics()
    val currentEv by rememberUpdatedState(evValue)
    val evDragStartCb by rememberUpdatedState(onEvDragStart)
    val evChangeCb by rememberUpdatedState(onEvChange)
    val evDragEndCb by rememberUpdatedState(onEvDragEnd)

    var isDraggingEv by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = reticle?.visible == true,
        enter = fadeIn(M3ExpressiveMotion.fastEffects()) + scaleIn(initialScale = 0.88f, animationSpec = M3ExpressiveMotion.fastSpatial()),
        exit = fadeOut(M3ExpressiveMotion.fastEffects()) + scaleOut(targetScale = 0.92f, animationSpec = M3ExpressiveMotion.fastEffects()),
        modifier = modifier.fillMaxSize(),
    ) {
        if (reticle != null) {
            val density = LocalDensity.current
            val ringRadiusPx = with(density) { 36.dp.toPx() }
            val trackHeightDp = 104.dp
            val trackHeightPx = with(density) { trackHeightDp.toPx() }
            val sliderPanelHalfHeightPx = with(density) { 74.dp.toPx() }
            val sliderWidthPx = with(density) { 54.dp.toPx() }

            val popScale = remember { Animatable(1.24f) }
            LaunchedEffect(reticle.xPx, reticle.yPx, reticle.isAeAfLocked) {
                popScale.snapTo(if (reticle.isAeAfLocked) 1.30f else 1.24f)
                popScale.animateTo(
                    targetValue = 1f,
                    animationSpec = M3ExpressiveMotion.fastSpatial(),
                )
            }

            // Gentle breathing pulse ONLY while actively focusing (zero Choreographer ticks once focused or locked)
            val isSeeking = reticle.isFocused == null && !reticle.isAeAfLocked
            val seekingPulse = remember { Animatable(1f) }
            LaunchedEffect(isSeeking) {
                if (isSeeking) {
                    seekingPulse.snapTo(0.96f)
                    seekingPulse.animateTo(
                        targetValue = 1.05f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(420),
                            repeatMode = RepeatMode.Reverse,
                        ),
                    )
                } else {
                    seekingPulse.snapTo(1f)
                }
            }
            val reticleColorState = animateColorAsState(
                targetValue = when {
                    reticle.isAeAfLocked -> MaterialTheme.colorScheme.secondary
                    reticle.isFocused == true -> FocusGreen
                    reticle.isFocused == false -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.72f)
                    else -> MaterialTheme.colorScheme.primary
                },
                animationSpec = M3ExpressiveMotion.fastEffects(),
                label = "reticle_color",
            )
            val reticleCookieAmpState = animateFloatAsState(
                targetValue = if (reticle.isAeAfLocked) 0.088f else 0f,
                animationSpec = M3ExpressiveMotion.fastSpatial(),
                label = "reticle_cookie_amp",
            )

            // Reactive EV slider physics
            val sliderContainerScaleState = animateFloatAsState(
                targetValue = if (isDraggingEv) 1.05f else 1f,
                animationSpec = M3ExpressiveMotion.fastSpatial(),
                label = "ev_panel_scale",
            )
            val trackStrokeDpState = animateDpAsState(
                targetValue = if (isDraggingEv) 6.dp else 4.dp,
                animationSpec = M3ExpressiveMotion.fastSpatial(),
                label = "ev_track_stroke",
            )
            val sunThumbSize by animateDpAsState(
                targetValue = if (isDraggingEv) 26.dp else 22.dp,
                animationSpec = M3ExpressiveMotion.fastSpatial(),
                label = "ev_thumb_size",
            )
            val sunCookieAmpState = animateFloatAsState(
                targetValue = if (isDraggingEv) 0.10f else 0f,
                animationSpec = M3ExpressiveMotion.fastSpatial(),
                label = "ev_sun_cookie_amp",
            )
            val smoothEvState = animateFloatAsState(
                targetValue = evValue.coerceIn(-3.0f, 3.0f),
                animationSpec = M3ExpressiveMotion.fastSpatial(),
                label = "ev_smooth",
            )

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val maxW = constraints.maxWidth.toFloat()
                val maxH = constraints.maxHeight.toFloat()
                val ringX = (reticle.xPx - ringRadiusPx).roundToInt()
                    .coerceIn(0, (maxW - ringRadiusPx * 2f).toInt().coerceAtLeast(0))
                val ringY = (reticle.yPx - ringRadiusPx).roundToInt()
                    .coerceIn(0, (maxH - ringRadiusPx * 2f).toInt().coerceAtLeast(0))

                val placeSliderOnLeft = reticle.normX > 0.70f
                val gapPx = with(density) { 10.dp.toPx() }
                val sliderX = if (placeSliderOnLeft) {
                    (ringX - sliderWidthPx - gapPx).roundToInt().coerceAtLeast(4)
                } else {
                    (ringX + ringRadiusPx * 2f + gapPx).roundToInt()
                        .coerceAtMost((maxW - sliderWidthPx - 4).toInt().coerceAtLeast(4))
                }
                val sliderY = (reticle.yPx - sliderPanelHalfHeightPx).roundToInt()
                    .coerceIn(8, (maxH - sliderPanelHalfHeightPx * 2f - 8f).toInt().coerceAtLeast(8))

                // Zero-clip GPU Canvas Focus Ring (Circle <-> Cookie-8) + Flat Matte AE/AF Lock Chip
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.offset { IntOffset(ringX, ringY) },
                ) {
                    val reticlePath = remember { Path() }
                    Canvas(
                        modifier = Modifier
                            .size(72.dp)
                            .graphicsLayer {
                                val s = popScale.value * seekingPulse.value
                                scaleX = s
                                scaleY = s
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            },
                    ) {
                        val color = reticleColorState.value
                        val amp = reticleCookieAmpState.value
                        val strokePx = if (reticle.isAeAfLocked) 2.5.dp.toPx() else 2.dp.toPx()
                        val radius = (size.minDimension - strokePx) * 0.5f
                        val center = Offset(size.width * 0.5f, size.height * 0.5f)
                        if (amp > 0.004f) {
                            reticlePath.reset()
                            reticlePath.addPath(
                                buildExpressivePolygonPath(
                                    size = size,
                                    lobes = 8,
                                    amplitude = amp,
                                    rotationDegrees = 22.5f,
                                )
                            )
                            drawPath(
                                path = reticlePath,
                                color = color,
                                style = Stroke(width = strokePx),
                            )
                        } else {
                            drawCircle(
                                color = color,
                                radius = radius,
                                center = center,
                                style = Stroke(width = strokePx),
                            )
                        }
                        drawCircle(
                            color = color,
                            radius = if (reticle.isAeAfLocked) 4.5.dp.toPx() else 3.dp.toPx(),
                            center = center,
                        )
                    }

                    AnimatedVisibility(
                        visible = reticle.isAeAfLocked,
                        enter = scaleIn(initialScale = 0.65f, animationSpec = M3ExpressiveMotion.fastSpatial()) +
                            fadeIn(M3ExpressiveMotion.fastEffects()),
                        exit = scaleOut(targetScale = 0.75f, animationSpec = M3ExpressiveMotion.fastEffects()) +
                            fadeOut(M3ExpressiveMotion.fastEffects()),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(modifier = Modifier.height(6.dp))
                            val lockInteraction = remember { MutableInteractionSource() }
                            val lockPressed by lockInteraction.collectIsPressedAsState()
                            val lockScale by animateFloatAsState(
                                targetValue = if (lockPressed) 0.90f else 1f,
                                animationSpec = M3ExpressiveMotion.fastSpatial(),
                                label = "lock_chip_scale",
                            )
                            Surface(
                                onClick = {
                                    haptics.tick()
                                    onUnlockAeAf()
                                },
                                interactionSource = lockInteraction,
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp,
                                modifier = Modifier
                                    .graphicsLayer {
                                        scaleX = lockScale
                                        scaleY = lockScale
                                    }
                                    .testTag("ae_af_lock_badge"),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lock,
                                        contentDescription = "Unlock AE/AF",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(11.dp),
                                    )
                                    Text(
                                        text = "AE/AF LOCK",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }
                    }
                }

                // Pure Matte Material 3 Expressive Draggable Focus-Reticle EV Slider
                val evAccent = MaterialTheme.colorScheme.secondary
                val evTrackInactive = MaterialTheme.colorScheme.surfaceContainerHighest

                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .offset { IntOffset(sliderX, sliderY) }
                        .width(54.dp)
                        .graphicsLayer {
                            val s = sliderContainerScaleState.value
                            scaleX = s
                            scaleY = s
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                isDraggingEv = true
                                evDragStartCb()
                                haptics.tick()

                                var evAccum = currentEv
                                var lastTickBucketEv = evAccum

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val active = event.changes.firstOrNull { it.id == down.id }
                                        ?: event.changes.firstOrNull()
                                    if (active == null || !active.pressed) {
                                        event.changes.forEach { it.consume() }
                                        break
                                    }
                                    val dy = active.position.y - active.previousPosition.y
                                    if (dy != 0f) {
                                        val deltaEv = (-dy / trackHeightPx) * 6.0f
                                        evAccum = (evAccum + deltaEv).coerceIn(-3.0f, 3.0f)
                                        val snapped = (evAccum * 10f).roundToInt() / 10f
                                        haptics.sliderDetent(lastTickBucketEv, snapped, 0.3f)
                                        if (abs(snapped - lastTickBucketEv) >= 0.3f) {
                                            lastTickBucketEv = snapped
                                        }
                                        evChangeCb(snapped)
                                    }
                                    active.consume()
                                }
                                isDraggingEv = false
                                evDragEndCb()
                            }
                        }
                        .testTag("reticle_ev_slider"),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 9.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (isDraggingEv) evAccent else Color.Transparent,
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp,
                        ) {
                            Text(
                                text = String.format("%+.1f", evValue),
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    isDraggingEv -> MaterialTheme.colorScheme.onSecondary
                                    evValue != 0f -> evAccent
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        val sunThumbBg = if (isDraggingEv) evAccent else MaterialTheme.colorScheme.secondaryContainer
                        Box(
                            modifier = Modifier
                                .width(28.dp)
                                .height(trackHeightDp),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        compositingStrategy = CompositingStrategy.ModulateAlpha
                                    },
                            ) {
                                val normPos = ((3.0f - smoothEvState.value) / 6.0f).coerceIn(0f, 1f)
                                val cx = size.width / 2f
                                val strokePx = trackStrokeDpState.value.toPx()
                                drawLine(
                                    color = evTrackInactive,
                                    start = Offset(cx, 8.dp.toPx()),
                                    end = Offset(cx, size.height - 8.dp.toPx()),
                                    strokeWidth = strokePx,
                                    cap = StrokeCap.Round,
                                )
                                val thumbY = 8.dp.toPx() + normPos * (size.height - 16.dp.toPx())
                                val midY = size.height / 2f
                                drawLine(
                                    color = evAccent,
                                    start = Offset(cx, midY),
                                    end = Offset(cx, thumbY),
                                    strokeWidth = strokePx,
                                    cap = StrokeCap.Round,
                                )
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.7f),
                                    radius = 2.dp.toPx(),
                                    center = Offset(cx, midY),
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .graphicsLayer {
                                        val normPos = ((3.0f - smoothEvState.value) / 6.0f).coerceIn(0f, 1f)
                                        translationY = normPos * (trackHeightPx - sunThumbSize.toPx())
                                        compositingStrategy = CompositingStrategy.ModulateAlpha
                                    }
                                    .size(sunThumbSize)
                                    .drawExpressiveShapeBackground(
                                        lobes = 8,
                                        amplitude = sunCookieAmpState.value,
                                        rotationDegrees = 22.5f,
                                        colorProvider = { sunThumbBg },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.WbSunny,
                                    contentDescription = "Adjust exposure brightness",
                                    tint = if (isDraggingEv) {
                                        MaterialTheme.colorScheme.onSecondary
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    },
                                    modifier = Modifier.size(if (isDraggingEv) 15.dp else 13.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LiveHistogramWidget(
    histogramFlow: StateFlow<LiveFrameAnalysisResult>,
    showZebraWarning: Boolean,
    showFocusPeakingBadge: Boolean,
    modifier: Modifier = Modifier,
) {
    val analysis by histogramFlow.collectAsStateWithLifecycle()
    val fillPath = remember { Path() }
    val linePath = remember { Path() }
    val redBrush = remember { Brush.verticalGradient(listOf(Color(0xFFEF4444).copy(alpha = 0.32f), Color.Transparent)) }
    val greenBrush = remember { Brush.verticalGradient(listOf(Color(0xFF22C55E).copy(alpha = 0.26f), Color.Transparent)) }
    val blueBrush = remember { Brush.verticalGradient(listOf(Color(0xFF3B82F6).copy(alpha = 0.30f), Color.Transparent)) }
    val lumaBrush = remember { Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent)) }

    Surface(
        modifier = modifier.width(146.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "RGB LUMA",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showZebraWarning && analysis.clippedHighlightPercent > 1.5f) {
                    Text(
                        text = "CLIP ${analysis.clippedHighlightPercent.toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (showFocusPeakingBadge) {
                    Text(
                        text = "PEAK ${analysis.edgeSharpnessScore.toInt()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ProTeal,
                    )
                }
            }

            Spacer(modifier = Modifier.height(5.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
            ) {
                drawHistogramCurve(analysis.redHistogram, redBrush, Color(0xFFEF4444).copy(alpha = 0.72f), fillPath, linePath)
                drawHistogramCurve(analysis.greenHistogram, greenBrush, Color(0xFF22C55E).copy(alpha = 0.68f), fillPath, linePath)
                drawHistogramCurve(analysis.blueHistogram, blueBrush, Color(0xFF3B82F6).copy(alpha = 0.76f), fillPath, linePath)
                drawHistogramCurve(analysis.lumaHistogram, lumaBrush, Color.White.copy(alpha = 0.92f), fillPath, linePath)
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "SHD ${analysis.crushedShadowPercent.toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "HI ${analysis.clippedHighlightPercent.toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (analysis.clippedHighlightPercent > 4f) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHistogramCurve(
    bins: List<Int>,
    fillBrush: Brush,
    strokeColor: Color,
    fillPath: Path,
    linePath: Path,
) {
    if (bins.size < 2) return
    fillPath.reset()
    linePath.reset()
    val stepX = size.width / (bins.size - 1)
    fillPath.moveTo(0f, size.height)

    bins.forEachIndexed { idx, value ->
        val x = idx * stepX
        val y = size.height - (value.coerceIn(0, 100) / 100f) * size.height
        if (idx == 0) {
            linePath.moveTo(x, y)
            fillPath.lineTo(x, y)
        } else {
            linePath.lineTo(x, y)
            fillPath.lineTo(x, y)
        }
    }
    fillPath.lineTo(size.width, size.height)
    fillPath.close()

    drawPath(path = fillPath, brush = fillBrush)
    drawPath(path = linePath, color = strokeColor, style = Stroke(width = 1.3.dp.toPx()))
}

@Composable
fun OpticalTelemetryHud(
    hdrMode: HdrMode,
    ultraHdrActive: Boolean,
    cinematicActive: Boolean,
    manual: ManualExposureSettings,
    telemetry: LiveTelemetry,
    modifier: Modifier = Modifier,
) {
    val isoText = manual.iso?.toString() ?: telemetry.estimatedIso.toString()
    val shutterText = if (manual.shutterSpeedNs != null) manual.formattedShutter() else telemetry.estimatedShutterLabel
    val wbText = manual.whiteBalanceKelvin?.let { "${it}K" } ?: "${telemetry.estimatedWbKelvin}K"
    val evText = String.format("%+.1f", manual.exposureCompensationEv)
    val lensSpec = if (telemetry.activeAperture != null) {
        "${telemetry.activeFocalLengthMm35}mm f/${String.format("%.1f", telemetry.activeAperture)}"
    } else {
        "${telemetry.activeFocalLengthMm35}mm"
    }
    val modeBadge = buildString {
        append(hdrMode.badge.uppercase())
        if (ultraHdrActive) append(" • UHDR")
        if (cinematicActive) append(" • CINE")
    }

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState = modeBadge,
                transitionSpec = {
                    (fadeIn(M3ExpressiveMotion.fastEffects()) + scaleIn(initialScale = 0.85f, animationSpec = M3ExpressiveMotion.fastSpatial())) togetherWith
                        fadeOut(M3ExpressiveMotion.fastEffects())
                },
                label = "hud_mode_badge",
            ) { badge ->
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hdrMode == HdrMode.HDR_PLUS_ENHANCED || ultraHdrActive) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            HudDivider()
            Text(
                text = lensSpec,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "ISO $isoText",
                style = MaterialTheme.typography.labelSmall,
                color = if (manual.iso != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = shutterText,
                style = MaterialTheme.typography.labelSmall,
                color = if (manual.shutterSpeedNs != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
            )
            AnimatedVisibility(
                visible = manual.exposureCompensationEv != 0f,
                enter = fadeIn(M3ExpressiveMotion.fastEffects()) + scaleIn(initialScale = 0.8f, animationSpec = M3ExpressiveMotion.fastSpatial()),
                exit = fadeOut(M3ExpressiveMotion.fastEffects()) + scaleOut(targetScale = 0.8f),
            ) {
                Text(
                    text = "${evText}EV",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Text(
                text = wbText,
                style = MaterialTheme.typography.labelSmall,
                color = if (manual.whiteBalanceKelvin != null) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun HudDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(10.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * Pure-GPU viewfinder overlay rendering minimal Pixel-style rounded corner brackets around
 * hardware-detected faces. Collects [facesFlow] in isolation so 15Hz face tracking never
 * triggers recompositions of the parent camera screen.
 */
@Composable
fun FaceDetectionOverlay(
    facesFlow: StateFlow<List<DetectedFace>>,
    isBacklit: Boolean,
    modifier: Modifier = Modifier,
) {
    val faces by facesFlow.collectAsStateWithLifecycle()
    val primaryColor by animateColorAsState(
        targetValue = if (isBacklit) PixelAmber else Color.White.copy(alpha = 0.90f),
        animationSpec = M3ExpressiveMotion.fastEffects(),
        label = "face_box_color",
    )

    if (faces.isEmpty()) return

    val bracketPath = remember { Path() }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("face_detection_overlay")
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    ) {
        val w = size.width
        val h = size.height
        val primaryStroke = 2.dp.toPx()
        val secondaryStroke = 1.4.dp.toPx()

        faces.forEachIndexed { idx, face ->
            val left = (face.left * w).coerceIn(0f, w)
            val top = (face.top * h).coerceIn(0f, h)
            val right = (face.right * w).coerceIn(left, w)
            val bottom = (face.bottom * h).coerceIn(top, h)
            val boxW = right - left
            val boxH = bottom - top
            if (boxW < 18f || boxH < 18f) return@forEachIndexed

            val isPrimary = idx == 0
            val color = if (isPrimary) primaryColor else Color.White.copy(alpha = 0.55f)
            val strokePx = if (isPrimary) primaryStroke else secondaryStroke
            val cornerLen = (minOf(boxW, boxH) * 0.24f).coerceIn(10.dp.toPx(), 24.dp.toPx())
            val cornerR = (cornerLen * 0.48f).coerceAtLeast(4.dp.toPx())

            // Draw all 4 subtle rounded corner brackets using a single reused Path (zero per-frame allocations)
            bracketPath.reset()
            // Top-Left
            bracketPath.moveTo(left, top + cornerLen)
            bracketPath.lineTo(left, top + cornerR)
            bracketPath.quadraticTo(left, top, left + cornerR, top)
            bracketPath.lineTo(left + cornerLen, top)
            // Top-Right
            bracketPath.moveTo(right - cornerLen, top)
            bracketPath.lineTo(right - cornerR, top)
            bracketPath.quadraticTo(right, top, right, top + cornerR)
            bracketPath.lineTo(right, top + cornerLen)
            // Bottom-Left
            bracketPath.moveTo(left, bottom - cornerLen)
            bracketPath.lineTo(left, bottom - cornerR)
            bracketPath.quadraticTo(left, bottom, left + cornerR, bottom)
            bracketPath.lineTo(left + cornerLen, bottom)
            // Bottom-Right
            bracketPath.moveTo(right - cornerLen, bottom)
            bracketPath.lineTo(right - cornerR, bottom)
            bracketPath.quadraticTo(right, bottom, right, bottom - cornerR)
            bracketPath.lineTo(right, bottom - cornerLen)

            drawPath(
                path = bracketPath,
                color = color,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
        }
    }
}
