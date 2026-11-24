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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.camera.model.ManualExposureSettings
import com.example.camera.model.TonePreset
import com.example.ui.viewmodel.TonePanelTab
import kotlin.math.roundToInt

/**
 * Material You 3 Expressive viewfinder panel providing direct real-time animated sliders for
 * Shadows, Highlights, Contrast, and Vibrancy alongside an animated Presets tab.
 */
@Composable
fun ToneAdjustmentsPanel(
    manual: ManualExposureSettings,
    activeTab: TonePanelTab,
    presets: List<TonePreset>,
    activePresetId: String?,
    onSelectTab: (TonePanelTab) -> Unit,
    onShadowsChange: (Float) -> Unit,
    onHighlightsChange: (Float) -> Unit,
    onContrastChange: (Float) -> Unit,
    onVibrancyChange: (Float) -> Unit,
    onResetTone: () -> Unit,
    onSavePreset: (String) -> Unit,
    onApplyPreset: (TonePreset?) -> Unit,
    onDeletePreset: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSaveDialog by remember { mutableStateOf(false) }

    ExpressiveSurfacePanel(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExpressiveSegmentedGroup(
                options = listOf(
                    TonePanelTab.ADJUST to "Tone",
                    TonePanelTab.PRESETS to if (presets.isEmpty()) "Presets" else "Presets (${presets.size})",
                ),
                selected = activeTab,
                onSelect = onSelectTab,
                modifier = Modifier.width(196.dp),
                testTagPrefix = "tone_tab",
            )

            AnimatedVisibility(
                visible = manual.isAnyToneOverride,
                enter = scaleIn(initialScale = 0.75f, animationSpec = M3ExpressiveMotion.fastSpatial()) +
                    fadeIn(M3ExpressiveMotion.fastEffects()),
                exit = scaleOut(targetScale = 0.75f, animationSpec = M3ExpressiveMotion.fastEffects()) +
                    fadeOut(M3ExpressiveMotion.fastEffects()),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExpressivePillChip(
                        label = "Save",
                        selected = true,
                        leadingIcon = Icons.Rounded.BookmarkAdd,
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedContentColor = MaterialTheme.colorScheme.onPrimary,
                        hapticStyle = ExpressiveHaptic.ACTION,
                        onClick = { showSaveDialog = true },
                        testTag = "tone_save_preset_button",
                    )
                    ExpressiveIconButton(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = "Reset tone sliders",
                        shapeStyle = ExpressiveButtonShape.CIRCLE_TO_CLOVER,
                        size = 36.dp,
                        iconSize = 18.dp,
                        inactiveContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        inactiveContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        hapticStyle = ExpressiveHaptic.ACTION,
                        onClick = onResetTone,
                        testTag = "tone_reset_button",
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        AnimatedContent(
            targetState = activeTab,
            transitionSpec = {
                val direction = if (targetState == TonePanelTab.PRESETS) 1 else -1
                (slideInHorizontally(M3ExpressiveMotion.defaultSpatial()) { it / 4 * direction } +
                    fadeIn(M3ExpressiveMotion.fastEffects())) togetherWith
                    (slideOutHorizontally(M3ExpressiveMotion.fastEffects()) { -it / 4 * direction } +
                        fadeOut(M3ExpressiveMotion.fastEffects()))
            },
            label = "tone_tab_content",
        ) { tab ->
            when (tab) {
                TonePanelTab.ADJUST -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ToneMiniSlider(
                                label = "Shadows",
                                value = manual.shadowBoost,
                                accent = MaterialTheme.colorScheme.primary,
                                onValueChange = onShadowsChange,
                                testTag = "vf_slider_shadows",
                                modifier = Modifier.weight(1f),
                            )
                            ToneMiniSlider(
                                label = "Highlights",
                                value = manual.highlightBoost,
                                accent = MaterialTheme.colorScheme.secondary,
                                onValueChange = onHighlightsChange,
                                testTag = "vf_slider_highlights",
                                modifier = Modifier.weight(1f),
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ToneMiniSlider(
                                label = "Contrast",
                                value = manual.contrastAdjust,
                                accent = MaterialTheme.colorScheme.onSurface,
                                onValueChange = onContrastChange,
                                testTag = "vf_slider_contrast",
                                modifier = Modifier.weight(1f),
                            )
                            ToneMiniSlider(
                                label = "Vibrancy",
                                value = manual.vibrancyAdjust,
                                accent = MaterialTheme.colorScheme.tertiary,
                                onValueChange = onVibrancyChange,
                                testTag = "vf_slider_vibrancy",
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                TonePanelTab.PRESETS -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 195.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SavePresetBannerCard(
                            manual = manual,
                            onClick = { showSaveDialog = true },
                        )

                        if (presets.isEmpty()) {
                            Text(
                                text = "No saved presets yet. Adjust Shadows, Highlights, Contrast, or Vibrancy and tap Save to add them to Looks.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                presets.forEachIndexed { idx, preset ->
                                    val pos = when {
                                        presets.size == 1 -> ExpressiveListPosition.SINGLE
                                        idx == 0 -> ExpressiveListPosition.TOP
                                        idx == presets.lastIndex -> ExpressiveListPosition.BOTTOM
                                        else -> ExpressiveListPosition.MIDDLE
                                    }
                                    ExpressivePresetRow(
                                        preset = preset,
                                        selected = preset.id == activePresetId,
                                        position = pos,
                                        onApply = { onApplyPreset(preset) },
                                        onDelete = { onDeletePreset(preset.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        SaveTonePresetDialog(
            suggestedName = "Look ${presets.size + 1}",
            onDismiss = { showSaveDialog = false },
            onSave = { name ->
                onSavePreset(name)
                showSaveDialog = false
            },
        )
    }
}

@Composable
private fun SavePresetBannerCard(
    manual: ManualExposureSettings,
    onClick: () -> Unit,
) {
    val haptics = rememberCameraHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scaleState = animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = M3ExpressiveMotion.fastSpatial(),
        label = "save_banner_scale",
    )
    val corner by animateDpAsState(
        targetValue = if (isPressed) 12.dp else 22.dp,
        animationSpec = M3ExpressiveMotion.fastSpatial(),
        label = "save_banner_corner",
    )

    Surface(
        onClick = {
            haptics.actionClick()
            onClick()
        },
        interactionSource = interactionSource,
        shape = RoundedCornerShape(corner),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val s = scaleState.value
                scaleX = s
                scaleY = s
            }
            .testTag("presets_tab_save_row"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val badgeBg = MaterialTheme.colorScheme.primary
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .drawExpressiveShapeBackground(
                            lobes = 8,
                            amplitude = 0.088f,
                            rotationDegrees = 22.5f,
                            colorProvider = { badgeBg },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BookmarkAdd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Text(
                    text = "Save current tone as Look",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                text = String.format(
                    "S%+d H%+d C%+d V%+d",
                    (manual.shadowBoost * 100f).roundToInt(),
                    (manual.highlightBoost * 100f).roundToInt(),
                    (manual.contrastAdjust * 100f).roundToInt(),
                    (manual.vibrancyAdjust * 100f).roundToInt(),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun ExpressivePresetRow(
    preset: TonePreset,
    selected: Boolean,
    position: ExpressiveListPosition,
    onApply: () -> Unit,
    onDelete: () -> Unit,
) {
    val haptics = rememberCameraHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scaleState = animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = M3ExpressiveMotion.fastSpatial(),
        label = "preset_row_scale",
    )
    val bg by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = M3ExpressiveMotion.fastEffects(),
        label = "preset_row_bg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = M3ExpressiveMotion.fastEffects(),
        label = "preset_row_fg",
    )
    val subFg by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = M3ExpressiveMotion.fastEffects(),
        label = "preset_row_sub_fg",
    )

    val rowShape = if (selected) {
        RoundedCornerShape(22.dp)
    } else {
        expressiveSegmentedCardShape(position = position, outerCorner = 20.dp, innerCorner = 6.dp)
    }

    Surface(
        onClick = {
            haptics.tick()
            onApply()
        },
        interactionSource = interactionSource,
        shape = rowShape,
        color = bg,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val s = scaleState.value
                scaleX = s
                scaleY = s
            }
            .testTag("preset_row_${preset.id}"),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = fg,
                )
                Text(
                    text = String.format(
                        "SHD %+d%% • HIL %+d%% • CTR %+d%% • VIB %+d%%",
                        (preset.shadowBoost * 100f).roundToInt(),
                        (preset.highlightBoost * 100f).roundToInt(),
                        (preset.contrastAdjust * 100f).roundToInt(),
                        (preset.vibrancyAdjust * 100f).roundToInt(),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = subFg,
                )
            }
            ExpressiveIconButton(
                icon = Icons.Rounded.DeleteOutline,
                contentDescription = "Delete ${preset.name}",
                shapeStyle = ExpressiveButtonShape.CIRCLE_TO_COOKIE,
                size = 34.dp,
                iconSize = 17.dp,
                inactiveContainerColor = Color.Transparent,
                inactiveContentColor = fg,
                hapticStyle = ExpressiveHaptic.ACTION,
                onClick = onDelete,
                testTag = "delete_preset_${preset.id}",
            )
        }
    }
}

@Composable
fun SaveTonePresetDialog(
    suggestedName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var nameText by remember(suggestedName) { mutableStateOf(suggestedName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = {
            Text(
                text = "Save Tone Look",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Saved presets appear in your Looks bar and Presets tab.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    label = { Text("Look name") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("preset_name_input"),
                )
            }
        },
        confirmButton = {
            ExpressiveFilledButton(
                text = "Save",
                onClick = { onSave(nameText) },
                testTag = "confirm_save_preset_button",
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
private fun ToneMiniSlider(
    label: String,
    value: Float,
    accent: Color,
    onValueChange: (Float) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    ExpressiveSlider(
        value = value.coerceIn(-1f, 1f),
        onValueChange = onValueChange,
        valueRange = -1f..1f,
        label = label,
        valueText = String.format("%+d%%", (value * 100f).roundToInt()),
        accentColor = accent,
        showCenterDetent = true,
        hapticStep = 0.10f,
        compact = true,
        sliderTestTag = testTag,
        modifier = modifier,
    )
}
