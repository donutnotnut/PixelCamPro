package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.camera.model.ManualExposureSettings
import com.example.camera.model.ProDialTab

@Composable
fun ProControlsPanel(
    manual: ManualExposureSettings,
    activeTab: ProDialTab,
    minFocusDiopter: Float,
    hardwareManualSupported: Boolean,
    onSelectTab: (ProDialTab) -> Unit,
    onEvChange: (Float) -> Unit,
    onShadowChange: (Float) -> Unit,
    onHighlightChange: (Float) -> Unit,
    onContrastChange: (Float) -> Unit,
    onVibrancyChange: (Float) -> Unit,
    onWbChange: (Int?) -> Unit,
    onIsoChange: (Int?) -> Unit,
    onShutterChange: (Long?) -> Unit,
    onFocusChange: (Float?) -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var resetRotation by remember { mutableFloatStateOf(0f) }

    ExpressiveSurfacePanel(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ProDialTab.entries.forEach { tab ->
                    val selected = tab == activeTab
                    val isOverridden = when (tab) {
                        ProDialTab.EV -> manual.exposureCompensationEv != 0f
                        ProDialTab.SHADOWS -> manual.shadowBoost != 0f
                        ProDialTab.HIGHLIGHTS -> manual.highlightBoost != 0f
                        ProDialTab.CONTRAST -> manual.contrastAdjust != 0f
                        ProDialTab.VIBRANCY -> manual.vibrancyAdjust != 0f
                        ProDialTab.WHITE_BALANCE -> manual.whiteBalanceKelvin != null
                        ProDialTab.ISO -> manual.iso != null
                        ProDialTab.SHUTTER -> manual.shutterSpeedNs != null
                        ProDialTab.FOCUS -> manual.focusDistanceDiopter != null
                    }
                    val valueSummary = when (tab) {
                        ProDialTab.EV -> String.format("%+.1f", manual.exposureCompensationEv)
                        ProDialTab.SHADOWS -> String.format("%+.0f", manual.shadowBoost * 100f)
                        ProDialTab.HIGHLIGHTS -> String.format("%+.0f", manual.highlightBoost * 100f)
                        ProDialTab.CONTRAST -> String.format("%+.0f", manual.contrastAdjust * 100f)
                        ProDialTab.VIBRANCY -> String.format("%+.0f", manual.vibrancyAdjust * 100f)
                        ProDialTab.WHITE_BALANCE -> manual.formattedWb()
                        ProDialTab.ISO -> manual.formattedIso()
                        ProDialTab.SHUTTER -> manual.formattedShutter()
                        ProDialTab.FOCUS -> manual.formattedFocus()
                    }

                    ExpressiveProTabTile(
                        shortLabel = tab.shortLabel,
                        valueSummary = valueSummary,
                        selected = selected,
                        isOverridden = isOverridden,
                        onClick = { onSelectTab(tab) },
                        testTag = "pro_tab_${tab.name.lowercase()}",
                    )
                }
            }

            AnimatedVisibility(
                visible = manual.isAnyManualOverride,
                enter = scaleIn(initialScale = 0.7f, animationSpec = M3ExpressiveMotion.fastSpatial()) +
                    fadeIn(M3ExpressiveMotion.fastEffects()),
                exit = scaleOut(targetScale = 0.7f, animationSpec = M3ExpressiveMotion.fastEffects()) +
                    fadeOut(M3ExpressiveMotion.fastEffects()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(6.dp))
                    ExpressiveIconButton(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = "Reset manual exposure",
                        shapeStyle = ExpressiveButtonShape.CIRCLE_TO_CLOVER,
                        size = 40.dp,
                        iconSize = 19.dp,
                        iconRotationDegrees = resetRotation,
                        inactiveContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        inactiveContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        hapticStyle = ExpressiveHaptic.ACTION,
                        onClick = {
                            resetRotation -= 360f
                            onResetAll()
                        },
                        testTag = "pro_reset_button",
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        AnimatedContent(
            targetState = activeTab,
            transitionSpec = {
                (slideInVertically(M3ExpressiveMotion.fastSpatial()) { it / 3 } +
                    fadeIn(M3ExpressiveMotion.fastEffects())) togetherWith
                    (slideOutVertically(M3ExpressiveMotion.fastEffects()) { -it / 4 } +
                        fadeOut(M3ExpressiveMotion.fastEffects()))
            },
            label = "pro_dial_content",
        ) { tab ->
            when (tab) {
                ProDialTab.EV -> {
                    ProContinuousSlider(
                        title = "Exposure Compensation",
                        valueText = String.format("%+.1f EV", manual.exposureCompensationEv),
                        value = manual.exposureCompensationEv,
                        valueRange = -3.0f..3.0f,
                        accent = MaterialTheme.colorScheme.secondary,
                        onValueChange = onEvChange,
                        testTag = "slider_ev",
                    )
                }

                ProDialTab.SHADOWS -> {
                    ProContinuousSlider(
                        title = "Shadows Lift / Depth",
                        valueText = String.format("%+.0f%%", manual.shadowBoost * 100f),
                        value = manual.shadowBoost,
                        valueRange = -1.0f..1.0f,
                        accent = MaterialTheme.colorScheme.primary,
                        onValueChange = onShadowChange,
                        testTag = "slider_shadows",
                    )
                }

                ProDialTab.HIGHLIGHTS -> {
                    ProContinuousSlider(
                        title = "Highlights Recovery / Boost",
                        valueText = String.format("%+.0f%%", manual.highlightBoost * 100f),
                        value = manual.highlightBoost,
                        valueRange = -1.0f..1.0f,
                        accent = MaterialTheme.colorScheme.secondary,
                        onValueChange = onHighlightChange,
                        testTag = "slider_highlights",
                    )
                }

                ProDialTab.CONTRAST -> {
                    ProContinuousSlider(
                        title = "Tone Contrast",
                        valueText = String.format("%+.0f%%", manual.contrastAdjust * 100f),
                        value = manual.contrastAdjust,
                        valueRange = -1.0f..1.0f,
                        accent = MaterialTheme.colorScheme.onSurface,
                        onValueChange = onContrastChange,
                        testTag = "slider_contrast",
                    )
                }

                ProDialTab.VIBRANCY -> {
                    ProContinuousSlider(
                        title = "Color Vibrancy",
                        valueText = String.format("%+.0f%%", manual.vibrancyAdjust * 100f),
                        value = manual.vibrancyAdjust,
                        valueRange = -1.0f..1.0f,
                        accent = MaterialTheme.colorScheme.tertiary,
                        onValueChange = onVibrancyChange,
                        testTag = "slider_vibrancy",
                    )
                }

                ProDialTab.WHITE_BALANCE -> {
                    val kelvinVal = (manual.whiteBalanceKelvin ?: 5400).toFloat()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProContinuousSlider(
                            title = "Color Temp",
                            valueText = manual.formattedWb(),
                            value = kelvinVal,
                            valueRange = 2500f..8500f,
                            accent = MaterialTheme.colorScheme.secondary,
                            onValueChange = { onWbChange((it / 100f).toInt() * 100) },
                            testTag = "slider_wb",
                        )
                        PresetStopRow(
                            options = listOf(
                                null to "Auto",
                                3200 to "3200K",
                                4400 to "4400K",
                                5400 to "5400K",
                                6500 to "6500K",
                                7800 to "7800K",
                            ),
                            selected = manual.whiteBalanceKelvin,
                            onSelect = onWbChange,
                        )
                    }
                }

                ProDialTab.ISO -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = if (hardwareManualSupported) "Sensor ISO Sensitivity" else "ISO (Sensor + Computational)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = manual.formattedIso(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        PresetStopRow(
                            options = listOf(
                                null to "Auto",
                                50 to "50",
                                100 to "100",
                                200 to "200",
                                400 to "400",
                                800 to "800",
                                1600 to "1600",
                                3200 to "3200",
                                6400 to "6400",
                            ),
                            selected = manual.iso,
                            onSelect = onIsoChange,
                        )
                    }
                }

                ProDialTab.SHUTTER -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Shutter Speed",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = manual.formattedShutter(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        PresetStopRow(
                            options = listOf(
                                null to "Auto",
                                250_000L to "1/4000",
                                1_000_000L to "1/1000",
                                2_000_000L to "1/500",
                                4_000_000L to "1/250",
                                8_000_000L to "1/125",
                                16_666_666L to "1/60",
                                33_333_333L to "1/30",
                                66_666_666L to "1/15",
                                250_000_000L to "1/4",
                                1_000_000_000L to "1\"",
                                4_000_000_000L to "4\"",
                            ),
                            selected = manual.shutterSpeedNs,
                            onSelect = onShutterChange,
                        )
                    }
                }

                ProDialTab.FOCUS -> {
                    val maxD = minFocusDiopter.coerceIn(2f, 15f)
                    val current = manual.focusDistanceDiopter ?: 0f
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProContinuousSlider(
                            title = "Manual Focus Distance",
                            valueText = manual.formattedFocus(),
                            value = current,
                            valueRange = 0f..maxD,
                            accent = MaterialTheme.colorScheme.tertiary,
                            onValueChange = onFocusChange,
                            testTag = "slider_focus",
                        )
                        PresetStopRow(
                            options = listOf(
                                null to "AF",
                                0.0f to "∞",
                                1.0f to "1.0m",
                                3.0f to "0.33m",
                                maxD to "Macro",
                            ),
                            selected = manual.focusDistanceDiopter,
                            onSelect = onFocusChange,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpressiveProTabTile(
    shortLabel: String,
    valueSummary: String,
    selected: Boolean,
    isOverridden: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    val haptics = rememberCameraHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scaleState = animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1f,
        animationSpec = M3ExpressiveMotion.fastSpatial(),
        label = "pro_tab_scale",
    )
    val corner by animateDpAsState(
        targetValue = when {
            isPressed -> 10.dp
            selected -> 22.dp
            else -> 12.dp
        },
        animationSpec = M3ExpressiveMotion.fastSpatial(),
        label = "pro_tab_corner",
    )
    val containerColor by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primary
            isOverridden -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = M3ExpressiveMotion.fastEffects(),
        label = "pro_tab_bg",
    )
    val labelColor by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
            isOverridden -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = M3ExpressiveMotion.fastEffects(),
        label = "pro_tab_lbl",
    )
    val valueColor by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.onPrimary
            isOverridden -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = M3ExpressiveMotion.fastEffects(),
        label = "pro_tab_val",
    )

    Surface(
        onClick = {
            haptics.tick()
            onClick()
        },
        interactionSource = interactionSource,
        shape = RoundedCornerShape(corner),
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier
            .graphicsLayer {
                val s = scaleState.value
                scaleX = s
                scaleY = s
            }
            .testTag(testTag),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                text = shortLabel,
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
            )
            Text(
                text = valueSummary,
                style = MaterialTheme.typography.labelMedium,
                color = valueColor,
            )
        }
    }
}

@Composable
private fun ProContinuousSlider(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    accent: Color,
    onValueChange: (Float) -> Unit,
    testTag: String,
) {
    ExpressiveSlider(
        value = value.coerceIn(valueRange.start, valueRange.endInclusive),
        onValueChange = onValueChange,
        valueRange = valueRange,
        label = title,
        valueText = valueText,
        accentColor = accent,
        hapticStep = (valueRange.endInclusive - valueRange.start) / 16f,
        sliderTestTag = testTag,
    )
}

@Composable
private fun <T> PresetStopRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (value, label) ->
            ExpressivePillChip(
                label = label,
                selected = selected == value,
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedContentColor = MaterialTheme.colorScheme.onPrimary,
                unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                onClick = { onSelect(value) },
            )
        }
    }
}
