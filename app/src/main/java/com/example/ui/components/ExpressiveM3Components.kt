package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Material 3 Expressive spring motion specifications tuned for crisp 120Hz responsiveness.
 */
object M3ExpressiveMotion {
    fun <T> fastSpatial(): SpringSpec<T> = spring(
        dampingRatio = 0.65f,
        stiffness = 760f,
    )

    fun <T> defaultSpatial(): SpringSpec<T> = spring(
        dampingRatio = 0.74f,
        stiffness = 460f,
    )

    fun <T> slowSpatial(): SpringSpec<T> = spring(
        dampingRatio = 0.80f,
        stiffness = 260f,
    )

    fun <T> fastEffects(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 1200f,
    )
}

/**
 * Flat, matte Material 3 Expressive surface panel with zero glow, zero border outline,
 * and zero animateContentSize layout thrashing so opening menus stays locked at 120fps.
 */
@Composable
fun ExpressiveSurfacePanel(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    cornerRadius: Dp = 28.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content,
        )
    }
}

/**
 * Authentic Material 3 Expressive Floating Pill Toolbar container (flat matte pill, zero border/glow).
 */
@Composable
fun ExpressiveFloatingToolbar(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentPadding: PaddingValues = PaddingValues(horizontal = 6.dp, vertical = 5.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * 120fps-optimized Material 3 Expressive reactive icon button with varied geometric shapes
 * (Scalloped Cookie, 4-Leaf Clover, Soft Burst, Rounded Square, Circle).
 * Uses zero-clipPath drawWithCache fills and defers scale/color/rotation reads to RenderNode layer phase.
 */
@Composable
fun ExpressiveIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    shapeStyle: ExpressiveButtonShape = ExpressiveButtonShape.CIRCLE_TO_SQUARE,
    badgeText: String? = null,
    size: Dp = 42.dp,
    iconSize: Dp = 20.dp,
    iconRotationDegrees: Float = 0f,
    activeContainerColor: Color = MaterialTheme.colorScheme.primary,
    activeContentColor: Color = MaterialTheme.colorScheme.onPrimary,
    inactiveContainerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    inactiveContentColor: Color = MaterialTheme.colorScheme.onSurface,
    hapticStyle: ExpressiveHaptic = ExpressiveHaptic.TICK,
    testTag: String? = null,
) {
    val haptics = rememberCameraHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scaleState = animateFloatAsState(
        targetValue = if (isPressed) 0.86f else 1f,
        animationSpec = M3ExpressiveMotion.fastSpatial(),
        label = "m3_icon_btn_scale",
    )
    val containerColorState = animateColorAsState(
        targetValue = if (active) activeContainerColor else inactiveContainerColor,
        animationSpec = M3ExpressiveMotion.fastEffects(),
        label = "m3_icon_btn_bg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (active) activeContentColor else inactiveContentColor,
        animationSpec = M3ExpressiveMotion.fastEffects(),
        label = "m3_icon_btn_fg",
    )
    val animatedRotationState = animateFloatAsState(
        targetValue = iconRotationDegrees,
        animationSpec = M3ExpressiveMotion.defaultSpatial(),
        label = "m3_icon_btn_rot",
    )

    val cornerInt = if (shapeStyle == ExpressiveButtonShape.CIRCLE_TO_SQUARE ||
        shapeStyle == ExpressiveButtonShape.SQUARE_TO_COOKIE
    ) {
        val cornerAnim by animateFloatAsState(
            targetValue = when (shapeStyle) {
                ExpressiveButtonShape.CIRCLE_TO_SQUARE -> when {
                    isPressed -> 24f
                    active -> 30f
                    else -> 50f
                }
                ExpressiveButtonShape.SQUARE_TO_COOKIE -> if (isPressed) 22f else 32f
                else -> 50f
            },
            animationSpec = M3ExpressiveMotion.fastSpatial(),
            label = "m3_icon_btn_corner",
        )
        cornerAnim.toInt().coerceIn(20, 50)
    } else {
        50
    }

    val quantAmp = if (shapeStyle != ExpressiveButtonShape.CIRCLE_TO_SQUARE) {
        val morphAmp by animateFloatAsState(
            targetValue = when (shapeStyle) {
                ExpressiveButtonShape.CIRCLE_TO_COOKIE -> when {
                    isPressed -> 0.11f
                    active -> 0.088f
                    else -> 0f
                }
                ExpressiveButtonShape.CIRCLE_TO_CLOVER -> when {
                    isPressed -> 0.15f
                    active -> 0.135f
                    else -> 0f
                }
                ExpressiveButtonShape.CIRCLE_TO_BURST -> when {
                    isPressed -> 0.12f
                    active -> 0.105f
                    else -> 0f
                }
                ExpressiveButtonShape.SQUARE_TO_COOKIE -> when {
                    isPressed -> 0.105f
                    active -> 0.088f
                    else -> 0f
                }
                ExpressiveButtonShape.COOKIE_PERSISTENT -> when {
                    isPressed -> 0.12f
                    active -> 0.095f
                    else -> 0.075f
                }
                ExpressiveButtonShape.CIRCLE_TO_SQUARE -> 0f
            },
            animationSpec = M3ExpressiveMotion.fastSpatial(),
            label = "m3_icon_btn_amp",
        )
        (morphAmp * 100f).toInt() / 100f
    } else {
        0f
    }

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    val s = scaleState.value
                    scaleX = s
                    scaleY = s
                }
                .drawExpressiveButtonBackground(
                    shapeStyle = shapeStyle,
                    quantAmp = quantAmp,
                    cornerPercent = cornerInt,
                    colorProvider = { containerColorState.value },
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = false, radius = size / 2),
                    role = Role.Button,
                    onClick = {
                        when (hapticStyle) {
                            ExpressiveHaptic.TICK -> haptics.tick()
                            ExpressiveHaptic.ACTION -> haptics.actionClick()
                            ExpressiveHaptic.NONE -> Unit
                        }
                        onClick()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier
                    .size(iconSize)
                    .graphicsLayer { rotationZ = animatedRotationState.value },
            )
            if (badgeText != null) {
                Text(
                    text = badgeText,
                    fontSize = 8.sp,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 1.dp),
                )
            }
        }
    }
}

enum class ExpressiveHaptic {
    TICK,
    ACTION,
    NONE,
}

/**
 * Flat matte Material 3 Expressive reactive pill/chip button.
 * Morphs cleanly between rounded rectangle (unselected) and full pill (selected) with spring press bounce.
 */
@Composable
fun ExpressivePillChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    showCheckWhenSelected: Boolean = false,
    unselectedCornerRadius: Dp = 14.dp,
    selectedCornerRadius: Dp = 24.dp,
    selectedContainerColor: Color = MaterialTheme.colorScheme.primary,
    selectedContentColor: Color = MaterialTheme.colorScheme.onPrimary,
    unselectedContainerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    unselectedContentColor: Color = MaterialTheme.colorScheme.onSurface,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    hapticStyle: ExpressiveHaptic = ExpressiveHaptic.TICK,
    testTag: String? = null,
) {
    val haptics = rememberCameraHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scaleState = animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = M3ExpressiveMotion.fastSpatial(),
        label = "m3_chip_scale",
    )
    val cornerRadius by animateDpAsState(
        targetValue = when {
            isPressed -> 10.dp
            selected -> selectedCornerRadius
            else -> unselectedCornerRadius
        },
        animationSpec = M3ExpressiveMotion.fastSpatial(),
        label = "m3_chip_corner",
    )
    val containerColor by animateColorAsState(
        targetValue = if (selected) selectedContainerColor else unselectedContainerColor,
        animationSpec = M3ExpressiveMotion.fastEffects(),
        label = "m3_chip_bg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) selectedContentColor else unselectedContentColor,
        animationSpec = M3ExpressiveMotion.fastEffects(),
        label = "m3_chip_fg",
    )

    Surface(
        onClick = {
            when (hapticStyle) {
                ExpressiveHaptic.TICK -> haptics.tick()
                ExpressiveHaptic.ACTION -> haptics.actionClick()
                ExpressiveHaptic.NONE -> Unit
            }
            onClick()
        },
        interactionSource = interactionSource,
        shape = RoundedCornerShape(cornerRadius),
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = modifier
            .graphicsLayer {
                val s = scaleState.value
                scaleX = s
                scaleY = s
            }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (showCheckWhenSelected && selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp),
                )
            } else if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailingContent != null) {
                trailingContent()
            }
        }
    }
}

/**
 * Authentic Material 3 Expressive Connected Button Group.
 * Unselected segments join with snug 6.dp inner corners; selected segment pops into a solid full pill.
 */
@Composable
fun <T> ExpressiveSegmentedGroup(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTagPrefix: String? = null,
) {
    val haptics = rememberCameraHaptics()
    Row(
        modifier = modifier.graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, (item, text) ->
            val isSelected = item == selected
            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()

            val scaleState = animateFloatAsState(
                targetValue = if (enabled && isPressed) 0.93f else 1f,
                animationSpec = M3ExpressiveMotion.fastSpatial(),
                label = "m3_seg_scale",
            )
            val isFirst = index == 0
            val isLast = index == options.lastIndex
            val innerCorner by animateDpAsState(
                targetValue = when {
                    isSelected -> 22.dp
                    enabled && isPressed -> 12.dp
                    else -> 6.dp
                },
                animationSpec = M3ExpressiveMotion.fastSpatial(),
                label = "m3_seg_inner_corner",
            )
            val containerColor by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                animationSpec = M3ExpressiveMotion.fastEffects(),
                label = "m3_seg_bg",
            )
            val contentColor by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = M3ExpressiveMotion.fastEffects(),
                label = "m3_seg_fg",
            )

            val segmentShape = RoundedCornerShape(
                topStart = if (isFirst) 22.dp else innerCorner,
                bottomStart = if (isFirst) 22.dp else innerCorner,
                topEnd = if (isLast) 22.dp else innerCorner,
                bottomEnd = if (isLast) 22.dp else innerCorner,
            )

            Surface(
                onClick = {
                    haptics.tick()
                    onSelect(item)
                },
                enabled = enabled,
                interactionSource = interactionSource,
                shape = segmentShape,
                color = containerColor,
                contentColor = contentColor,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer {
                        val s = scaleState.value
                        scaleX = s
                        scaleY = s
                    }
                    .then(
                        if (testTagPrefix != null) Modifier.testTag("${testTagPrefix}_$index") else Modifier
                    ),
            ) {
                Box(
                    modifier = Modifier.padding(vertical = 9.dp, horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Pure matte Material 3 Expressive thick-capsule slider with zero halo/glow:
 * Defers scale/swelling reads to DrawScope & graphicsLayer for 120fps drag tracking.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressiveSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    modifier: Modifier = Modifier,
    label: String? = null,
    valueText: String? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    onAccentColor: Color = MaterialTheme.colorScheme.onPrimary,
    inactiveTrackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    showCenterDetent: Boolean = valueRange.start < 0f && valueRange.endInclusive > 0f,
    hapticStep: Float = (valueRange.endInclusive - valueRange.start) / 16f,
    compact: Boolean = false,
    sliderTestTag: String? = null,
) {
    val haptics = rememberCameraHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isDragged by interactionSource.collectIsDraggedAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val isInteracting = isDragged || isPressed

    val safeValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val fraction = ((safeValue - valueRange.start) / span).coerceIn(0f, 1f)
    val centerFraction = if (showCenterDetent) {
        ((0f - valueRange.start) / span).coerceIn(0f, 1f)
    } else 0f

    val trackFractionState = animateFloatAsState(
        targetValue = if (isInteracting) 1f else 0f,
        animationSpec = M3ExpressiveMotion.fastSpatial(),
        label = "m3_slider_interact",
    )
    val badgeBg by animateColorAsState(
        targetValue = if (isInteracting) accentColor else Color.Transparent,
        animationSpec = M3ExpressiveMotion.fastEffects(),
        label = "m3_slider_badge_bg",
    )
    val isNonZero = if (showCenterDetent) kotlin.math.abs(safeValue) > 0.005f else safeValue > valueRange.start + 0.005f
    val badgeTextColor by animateColorAsState(
        targetValue = when {
            isInteracting -> onAccentColor
            isNonZero -> accentColor
            else -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = M3ExpressiveMotion.fastEffects(),
        label = "m3_slider_badge_fg",
    )

    val maxTrackHeight = if (compact) 14.dp else 18.dp
    val minTrackHeight = if (compact) 10.dp else 14.dp
    val maxThumbHeight = if (compact) 28.dp else 34.dp
    val onSurfaceDot = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)

    Column(modifier = modifier) {
        if (label != null || valueText != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (label != null) {
                    Text(
                        text = label,
                        style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                        color = if (isInteracting) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (valueText != null) {
                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                val s = 1f + trackFractionState.value * 0.08f
                                scaleX = s
                                scaleY = s
                            }
                            .clip(CircleShape)
                            .background(badgeBg)
                            .padding(horizontal = if (isInteracting) 8.dp else 2.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = valueText,
                            style = MaterialTheme.typography.labelMedium,
                            color = badgeTextColor,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(if (compact) 2.dp else 4.dp))
        }

        Slider(
            value = safeValue,
            onValueChange = { next ->
                haptics.sliderDetent(safeValue, next, hapticStep)
                onValueChange(next)
            },
            valueRange = valueRange,
            interactionSource = interactionSource,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = inactiveTrackColor,
            ),
            thumb = {
                Box(
                    modifier = Modifier.size(width = 16.dp, height = maxThumbHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 4.dp, height = maxThumbHeight)
                            .graphicsLayer {
                                val t = trackFractionState.value
                                scaleX = 1.12f - t * 0.34f
                                scaleY = 0.80f + t * 0.20f
                            }
                            .clip(CircleShape)
                            .background(accentColor),
                    )
                }
            },
            track = {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(maxTrackHeight)
                        .graphicsLayer {
                            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                        },
                ) {
                    val t = trackFractionState.value
                    val h = minTrackHeight.toPx() + (maxTrackHeight.toPx() - minTrackHeight.toPx()) * t
                    val topY = (size.height - h) * 0.5f
                    val centerY = size.height * 0.5f
                    val w = size.width
                    val radius = CornerRadius(h * 0.5f, h * 0.5f)
                    val thumbX = (fraction * w).coerceIn(0f, w)
                    val gap = (5.dp.toPx() + 1.dp.toPx() * t)

                    val leftEnd = (thumbX - gap).coerceAtLeast(0f)
                    val rightStart = (thumbX + gap).coerceAtMost(w)

                    if (leftEnd > 1f) {
                        drawRoundRect(
                            color = inactiveTrackColor,
                            topLeft = Offset(0f, topY),
                            size = Size(leftEnd, h),
                            cornerRadius = radius,
                        )
                    }
                    if (w - rightStart > 1f) {
                        drawRoundRect(
                            color = inactiveTrackColor,
                            topLeft = Offset(rightStart, topY),
                            size = Size(w - rightStart, h),
                            cornerRadius = radius,
                        )
                    }

                    if (showCenterDetent) {
                        val cx = centerFraction * w
                        if (thumbX > cx + gap) {
                            val start = cx
                            val end = (thumbX - gap).coerceAtLeast(start)
                            if (end - start > 1f) {
                                drawRoundRect(
                                    color = accentColor,
                                    topLeft = Offset(start, topY),
                                    size = Size(end - start, h),
                                    cornerRadius = radius,
                                )
                            }
                        } else if (thumbX < cx - gap) {
                            val start = (thumbX + gap).coerceAtMost(cx)
                            val end = cx
                            if (end - start > 1f) {
                                drawRoundRect(
                                    color = accentColor,
                                    topLeft = Offset(start, topY),
                                    size = Size(end - start, h),
                                    cornerRadius = radius,
                                )
                            }
                        }
                        if (kotlin.math.abs(thumbX - cx) > gap + 2.dp.toPx()) {
                            drawCircle(
                                color = onSurfaceDot,
                                radius = (h * 0.2f).coerceIn(2.dp.toPx(), 3.dp.toPx()),
                                center = Offset(cx, centerY),
                            )
                        }
                    } else if (leftEnd > 1f) {
                        drawRoundRect(
                            color = accentColor,
                            topLeft = Offset(0f, topY),
                            size = Size(leftEnd, h),
                            cornerRadius = radius,
                        )
                    }

                    val dotRadius = (h * 0.15f).coerceIn(1.6.dp.toPx(), 2.4.dp.toPx())
                    val endDotX = w - h * 0.5f
                    if (endDotX > rightStart + dotRadius * 2f) {
                        drawCircle(
                            color = accentColor.copy(alpha = 0.6f),
                            radius = dotRadius,
                            center = Offset(endDotX, centerY),
                        )
                    }
                    if (showCenterDetent) {
                        val startDotX = h * 0.5f
                        if (startDotX < leftEnd - dotRadius * 2f) {
                            drawCircle(
                                color = accentColor.copy(alpha = 0.6f),
                                radius = dotRadius,
                                center = Offset(startDotX, centerY),
                            )
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 30.dp else 36.dp)
                .then(if (sliderTestTag != null) Modifier.testTag(sliderTestTag) else Modifier),
        )
    }
}

/**
 * Material 3 Expressive Switch using Material 3's built-in spring thumb physics (zero AnimatedContent overhead).
 */
@Composable
fun ExpressiveSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = rememberCameraHaptics()
    Switch(
        checked = checked,
        onCheckedChange = { next ->
            haptics.tick()
            onCheckedChange(next)
        },
        enabled = enabled,
        modifier = modifier,
        thumbContent = {
            Icon(
                imageVector = if (checked) Icons.Rounded.Check else Icons.Rounded.Close,
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize),
            )
        },
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedIconColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            uncheckedIconColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    )
}

/**
 * Flat matte Material 3 Expressive filled button with layer-deferred spring press bounce & corner morph.
 */
@Composable
fun ExpressiveFilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    testTag: String? = null,
) {
    val haptics = rememberCameraHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scaleState = animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = M3ExpressiveMotion.fastSpatial(),
        label = "m3_btn_scale",
    )
    val corner by animateDpAsState(
        targetValue = if (isPressed) 12.dp else 28.dp,
        animationSpec = M3ExpressiveMotion.fastSpatial(),
        label = "m3_btn_corner",
    )

    Button(
        onClick = {
            haptics.actionClick()
            onClick()
        },
        interactionSource = interactionSource,
        shape = RoundedCornerShape(corner),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            hoveredElevation = 0.dp,
            focusedElevation = 0.dp,
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        modifier = modifier
            .graphicsLayer {
                val s = scaleState.value
                scaleX = s
                scaleY = s
            }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Material 3 Expressive outlined button with layer-deferred spring press bounce & corner morph.
 */
@Composable
fun ExpressiveOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val haptics = rememberCameraHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scaleState = animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = M3ExpressiveMotion.fastSpatial(),
        label = "m3_obtn_scale",
    )
    val corner by animateDpAsState(
        targetValue = if (isPressed) 12.dp else 28.dp,
        animationSpec = M3ExpressiveMotion.fastSpatial(),
        label = "m3_obtn_corner",
    )

    OutlinedButton(
        onClick = {
            haptics.tick()
            onClick()
        },
        interactionSource = interactionSource,
        shape = RoundedCornerShape(corner),
        modifier = modifier
            .graphicsLayer {
                val s = scaleState.value
                scaleX = s
                scaleY = s
            }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Position inside an Android 16 / Material 3 Expressive segmented card stack.
 */
enum class ExpressiveListPosition {
    TOP,
    MIDDLE,
    BOTTOM,
    SINGLE,
}

private val TopSegmentedCardShape = RoundedCornerShape(
    topStart = 24.dp,
    topEnd = 24.dp,
    bottomStart = 6.dp,
    bottomEnd = 6.dp,
)
private val MiddleSegmentedCardShape = RoundedCornerShape(6.dp)
private val BottomSegmentedCardShape = RoundedCornerShape(
    topStart = 6.dp,
    topEnd = 6.dp,
    bottomStart = 24.dp,
    bottomEnd = 24.dp,
)
private val SingleSegmentedCardShape = RoundedCornerShape(24.dp)

fun expressiveSegmentedCardShape(
    position: ExpressiveListPosition,
    outerCorner: Dp = 24.dp,
    innerCorner: Dp = 6.dp,
): RoundedCornerShape {
    if (outerCorner == 24.dp && innerCorner == 6.dp) {
        return when (position) {
            ExpressiveListPosition.TOP -> TopSegmentedCardShape
            ExpressiveListPosition.MIDDLE -> MiddleSegmentedCardShape
            ExpressiveListPosition.BOTTOM -> BottomSegmentedCardShape
            ExpressiveListPosition.SINGLE -> SingleSegmentedCardShape
        }
    }
    return when (position) {
        ExpressiveListPosition.TOP -> RoundedCornerShape(
            topStart = outerCorner,
            topEnd = outerCorner,
            bottomStart = innerCorner,
            bottomEnd = innerCorner,
        )
        ExpressiveListPosition.MIDDLE -> RoundedCornerShape(innerCorner)
        ExpressiveListPosition.BOTTOM -> RoundedCornerShape(
            topStart = innerCorner,
            topEnd = innerCorner,
            bottomStart = outerCorner,
            bottomEnd = outerCorner,
        )
        ExpressiveListPosition.SINGLE -> RoundedCornerShape(outerCorner)
    }
}
