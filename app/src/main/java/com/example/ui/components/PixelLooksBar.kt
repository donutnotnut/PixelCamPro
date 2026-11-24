package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.camera.model.TonePreset

/**
 * Flat matte Material 3 Expressive horizontal Looks carousel displaying user-saved Tone Presets.
 * Unselected tiles are crisp rounded rectangles; selected items morph into bold solid pills.
 */
@Composable
fun PixelLooksBar(
    presets: List<TonePreset>,
    activePresetId: String?,
    hasUnsavedToneChanges: Boolean,
    onSelectPreset: (TonePreset?) -> Unit,
    onDeletePreset: (String) -> Unit,
    onRequestSaveCurrent: () -> Unit,
    onOpenToneSliders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberCameraHaptics()
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "default_neutral") {
            val selected = activePresetId == null && !hasUnsavedToneChanges
            ExpressivePillChip(
                label = "Default",
                selected = selected,
                showCheckWhenSelected = true,
                unselectedCornerRadius = 12.dp,
                selectedCornerRadius = 24.dp,
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedContentColor = MaterialTheme.colorScheme.onPrimary,
                unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                onClick = { onSelectPreset(null) },
                testTag = "look_chip_default",
                modifier = Modifier.animateItem(
                    placementSpec = M3ExpressiveMotion.defaultSpatial(),
                ),
            )
        }

        if (hasUnsavedToneChanges && activePresetId == null) {
            item(key = "save_current_look") {
                ExpressivePillChip(
                    label = "Save Look",
                    selected = true,
                    leadingIcon = Icons.Rounded.Add,
                    selectedCornerRadius = 24.dp,
                    selectedContainerColor = MaterialTheme.colorScheme.tertiary,
                    selectedContentColor = MaterialTheme.colorScheme.onTertiary,
                    hapticStyle = ExpressiveHaptic.ACTION,
                    onClick = onRequestSaveCurrent,
                    testTag = "looks_save_current_chip",
                    modifier = Modifier.animateItem(
                        placementSpec = M3ExpressiveMotion.defaultSpatial(),
                    ),
                )
            }
        }

        items(presets, key = { it.id }) { preset ->
            val selected = preset.id == activePresetId
            ExpressivePillChip(
                label = preset.name,
                selected = selected,
                showCheckWhenSelected = true,
                unselectedCornerRadius = 12.dp,
                selectedCornerRadius = 24.dp,
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedContentColor = MaterialTheme.colorScheme.onPrimary,
                unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                onClick = { onSelectPreset(preset) },
                trailingContent = {
                    val deleteBg = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .drawExpressiveShapeBackground(
                                lobes = 8,
                                amplitude = 0.088f,
                                rotationDegrees = 22.5f,
                                colorProvider = { deleteBg },
                            )
                            .clickable(
                                onClick = {
                                    haptics.actionClick()
                                    onDeletePreset(preset.id)
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Delete ${preset.name}",
                            tint = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(12.dp),
                        )
                    }
                },
                testTag = "look_chip_${preset.id}",
                modifier = Modifier.animateItem(
                    placementSpec = M3ExpressiveMotion.defaultSpatial(),
                ),
            )
        }

        if (presets.isEmpty() && !hasUnsavedToneChanges) {
            item(key = "empty_looks_hint") {
                ExpressivePillChip(
                    label = "Adjust Tone to save Looks",
                    selected = false,
                    leadingIcon = Icons.Rounded.Tune,
                    unselectedCornerRadius = 14.dp,
                    unselectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    unselectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = onOpenToneSliders,
                    testTag = "looks_empty_hint",
                    modifier = Modifier.animateItem(
                        placementSpec = M3ExpressiveMotion.defaultSpatial(),
                    ),
                )
            }
        }
    }
}
