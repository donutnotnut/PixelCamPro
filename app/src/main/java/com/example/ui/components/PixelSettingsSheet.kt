package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BurstMode
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.HdrEnhancedSelect
import androidx.compose.material.icons.rounded.MovieFilter
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.camera.model.AspectRatioMode
import com.example.camera.model.GridOverlayType
import com.example.camera.model.HdrMode
import com.example.camera.model.HdrPipelineConfig
import com.example.camera.model.NightSightMode
import com.example.camera.model.PixelExtensionMode
import com.example.data.PersistedCameraPreferences
import kotlin.math.roundToInt

/**
 * 120fps Full-screen Android 16 / Material 3 Expressive Camera Settings screen.
 * Backed by LazyColumn with zero-clipPath GPU-cached shape badges, granular immutable parameters,
 * and layer-deferred animations so scrolling and tapping remain completely fluid at 120Hz.
 */
@Composable
fun PixelSettingsSheet(
    showHorizonLevel: Boolean,
    gridOverlay: GridOverlayType,
    aspectRatio: AspectRatioMode,
    hdrMode: HdrMode,
    nightSightMode: NightSightMode = NightSightMode.AUTO,
    isNightSightActive: Boolean = false,
    pixelCameraServicesEnabled: Boolean = false,
    pixelExtensionsSupported: Boolean = false,
    isPixelNightOnlyDevice: Boolean = false,
    hasCheckedPixelExtensions: Boolean = false,
    activePixelExtensionMode: PixelExtensionMode = PixelExtensionMode.NONE,
    isPixelServicesLocked: Boolean = false,
    isAnalysisActiveInSession: Boolean = true,
    nativeUltraHdrSupported: Boolean = false,
    nativeUltraHdrActive: Boolean = false,
    hdrConfig: HdrPipelineConfig,
    showHistogram: Boolean,
    showZebraHighlights: Boolean,
    showFocusPeaking: Boolean,
    onDismiss: () -> Unit,
    onTogglePixelCameraServices: (Boolean) -> Unit = {},
    onUpdatePrefs: ((PersistedCameraPreferences) -> PersistedCameraPreferences) -> Unit,
) {
    BackHandler { onDismiss() }

    val haptics = rememberCameraHaptics()
    var showGridDialog by remember { mutableStateOf(false) }
    var showAspectDialog by remember { mutableStateOf(false) }
    var showHdrModeDialog by remember { mutableStateOf(false) }
    var showNightSightDialog by remember { mutableStateOf(false) }
    var showBurstDialog by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }
    val effectiveAdvancedExpanded = advancedExpanded && !isPixelServicesLocked

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Material 3 Expressive Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExpressiveIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.cd_close),
                shapeStyle = ExpressiveButtonShape.CIRCLE_TO_SQUARE,
                size = 44.dp,
                iconSize = 22.dp,
                inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                inactiveContentColor = MaterialTheme.colorScheme.onSurface,
                onClick = onDismiss,
                testTag = "settings_close_button",
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Camera settings",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // Section: General
            item(key = "header_general", contentType = "header") {
                SectionHeader("General", topSpacing = false)
            }
            item(key = "row_horizon", contentType = "switch") {
                PixelSwitchRow(
                    icon = Icons.Rounded.Straighten,
                    title = "Framing hints",
                    subtitle = "Horizon spirit level overlay on viewfinder",
                    checked = showHorizonLevel,
                    position = ExpressiveListPosition.TOP,
                    badgeShape = ExpressiveButtonShape.CIRCLE_TO_CLOVER,
                    onCheckedChange = { v -> onUpdatePrefs { it.copy(showHorizonLevel = v) } },
                )
            }
            item(key = "row_grid", contentType = "picker") {
                PixelPickerRow(
                    icon = Icons.Rounded.GridOn,
                    title = "Grid type",
                    valueLabel = gridOverlay.label,
                    position = ExpressiveListPosition.MIDDLE,
                    badgeShape = ExpressiveButtonShape.CIRCLE_TO_SQUARE,
                    onClick = { showGridDialog = true },
                )
            }
            item(key = "row_aspect", contentType = "picker") {
                PixelPickerRow(
                    icon = Icons.Rounded.AspectRatio,
                    title = "Camera photo resolution aspect",
                    valueLabel = aspectRatio.label,
                    position = ExpressiveListPosition.MIDDLE,
                    badgeShape = ExpressiveButtonShape.COOKIE_PERSISTENT,
                    onClick = { showAspectDialog = true },
                )
            }
            item(key = "row_mirror", contentType = "switch") {
                PixelSwitchRow(
                    icon = Icons.Rounded.Flip,
                    title = "Save selfie as previewed",
                    subtitle = "Mirror front camera captures horizontally",
                    checked = hdrConfig.mirrorFrontCamera,
                    position = ExpressiveListPosition.BOTTOM,
                    badgeShape = ExpressiveButtonShape.CIRCLE_TO_BURST,
                    onCheckedChange = { enabled ->
                        onUpdatePrefs { it.copy(hdrConfig = it.hdrConfig.copy(mirrorFrontCamera = enabled)) }
                    },
                )
            }

            // Section: Photo & HDR+
            item(key = "header_hdr", contentType = "header") {
                SectionHeader("Photo & HDR+")
            }
            item(key = "row_pixel_services", contentType = "switch") {
                val servicesSubtitle = when {
                    pixelCameraServicesEnabled && activePixelExtensionMode != PixelExtensionMode.NONE ->
                        "Active (${activePixelExtensionMode.badge}) • Locks Pro controls, custom HDR+ & live HUD"
                    pixelCameraServicesEnabled && isPixelNightOnlyDevice -> {
                        val daylightEngine = if (nativeUltraHdrActive) {
                            "custom HDR+ & Native Ultra HDR"
                        } else {
                            "custom HDR+ engine"
                        }
                        "Night Sight only on this device • Auto-activates in low light; daylight uses $daylightEngine"
                    }
                    pixelCameraServicesEnabled && hasCheckedPixelExtensions && !pixelExtensionsSupported ->
                        "Unavailable on this lens/device — using custom HDR+ engine"
                    pixelCameraServicesEnabled ->
                        "Enabled • Locks Pro controls & custom HDR+ while active"
                    isPixelNightOnlyDevice ->
                        "Use Google's Pixel Night Sight in low light (daylight stays on custom HDR+ & Ultra HDR)"
                    else ->
                        "Use Google's native Pixel Camera Services (locks Pro controls while active)"
                }
                PixelSwitchRow(
                    icon = Icons.Rounded.AutoAwesome,
                    title = "Pixel Camera Services",
                    subtitle = servicesSubtitle,
                    checked = pixelCameraServicesEnabled,
                    position = ExpressiveListPosition.TOP,
                    badgeShape = ExpressiveButtonShape.COOKIE_PERSISTENT,
                    accentContainer = MaterialTheme.colorScheme.primaryContainer,
                    accentContent = MaterialTheme.colorScheme.onPrimaryContainer,
                    rowTestTag = "settings_pixel_services_switch",
                    onCheckedChange = onTogglePixelCameraServices,
                )
            }
            item(key = "row_night_sight", contentType = "picker") {
                val usesPixelNight = pixelCameraServicesEnabled && (isPixelNightOnlyDevice || isPixelServicesLocked)
                PixelPickerRow(
                    icon = Icons.Rounded.NightsStay,
                    title = "Low Light / Night Sight",
                    valueLabel = when (nightSightMode) {
                        NightSightMode.AUTO -> if (usesPixelNight) {
                            "Auto (Pixel Night in low light)"
                        } else {
                            "Auto (long shutter in low light)"
                        }
                        NightSightMode.ON -> if (usesPixelNight) {
                            "Always On (Pixel Night)"
                        } else {
                            "Always On (long shutter)"
                        }
                        NightSightMode.OFF -> "Off"
                    },
                    position = ExpressiveListPosition.MIDDLE,
                    badgeShape = ExpressiveButtonShape.CIRCLE_TO_COOKIE,
                    accentContainer = MaterialTheme.colorScheme.secondaryContainer,
                    accentContent = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = { showNightSightDialog = true },
                )
            }
            item(key = "row_hdr_mode", contentType = "picker") {
                PixelPickerRow(
                    icon = Icons.Rounded.HdrEnhancedSelect,
                    title = "HDR+ control",
                    valueLabel = when {
                        isNightSightActive -> "Off (disabled in Low Light)"
                        isPixelServicesLocked && hdrMode != HdrMode.OFF -> "${hdrMode.label} (Pixel HDR)"
                        else -> hdrMode.label
                    },
                    position = ExpressiveListPosition.MIDDLE,
                    badgeShape = ExpressiveButtonShape.COOKIE_PERSISTENT,
                    accentContainer = MaterialTheme.colorScheme.tertiaryContainer,
                    accentContent = MaterialTheme.colorScheme.onTertiaryContainer,
                    enabled = !isNightSightActive,
                    onClick = { showHdrModeDialog = true },
                )
            }
            item(key = "row_ultrahdr", contentType = "switch") {
                val ultraHdrEnabled = !isNightSightActive && !isPixelServicesLocked
                PixelSwitchRow(
                    icon = Icons.Rounded.AutoFixHigh,
                    title = "Ultra HDR",
                    subtitle = when {
                        isPixelServicesLocked -> "Managed by Pixel Camera Services"
                        isNightSightActive -> "Disabled automatically in Low Light mode"
                        nativeUltraHdrActive -> "Active (Native hardware Ultra HDR) • Saved to DCIM/Camera"
                        nativeUltraHdrSupported -> "Native hardware Ultra HDR (Android 14+) • Saved to DCIM/Camera"
                        else -> "Save 10-bit Gainmap HDR metadata to DCIM/Camera (Android 14+)"
                    },
                    checked = ultraHdrEnabled && hdrConfig.ultraHdrEnabled,
                    position = ExpressiveListPosition.MIDDLE,
                    badgeShape = ExpressiveButtonShape.CIRCLE_TO_BURST,
                    accentContainer = MaterialTheme.colorScheme.tertiaryContainer,
                    accentContent = MaterialTheme.colorScheme.onTertiaryContainer,
                    enabled = ultraHdrEnabled,
                    onCheckedChange = { enabled ->
                        onUpdatePrefs { it.copy(hdrConfig = it.hdrConfig.copy(ultraHdrEnabled = enabled)) }
                    },
                )
            }
            item(key = "row_cinematic", contentType = "switch") {
                PixelSwitchRow(
                    icon = Icons.Rounded.MovieFilter,
                    title = "Cinematic effect",
                    subtitle = "Slightly deeper exposure, richer contrast & filmic vibrancy out of the box",
                    checked = hdrConfig.cinematicEffectEnabled,
                    position = ExpressiveListPosition.MIDDLE,
                    badgeShape = ExpressiveButtonShape.CIRCLE_TO_COOKIE,
                    accentContainer = MaterialTheme.colorScheme.secondaryContainer,
                    accentContent = MaterialTheme.colorScheme.onSecondaryContainer,
                    onCheckedChange = { enabled ->
                        onUpdatePrefs { it.copy(hdrConfig = it.hdrConfig.copy(cinematicEffectEnabled = enabled)) }
                    },
                )
            }
            item(key = "row_burst", contentType = "picker") {
                val burstEnabled = !isNightSightActive && !isPixelServicesLocked
                PixelPickerRow(
                    icon = Icons.Rounded.BurstMode,
                    title = "HDR+ burst frames",
                    valueLabel = when {
                        isPixelServicesLocked -> "Managed by Pixel Camera Services"
                        isNightSightActive -> "Disabled in Low Light"
                        else -> "${hdrConfig.burstFrameCount} frames"
                    },
                    position = ExpressiveListPosition.MIDDLE,
                    badgeShape = ExpressiveButtonShape.CIRCLE_TO_CLOVER,
                    enabled = burstEnabled,
                    onClick = { showBurstDialog = true },
                )
            }
            item(key = "row_alignment", contentType = "switch") {
                PixelSwitchRow(
                    icon = Icons.Rounded.CenterFocusStrong,
                    title = "1-px tile motion alignment",
                    subtitle = if (isPixelServicesLocked) {
                        "Managed by Pixel Camera Services"
                    } else {
                        "Align handheld frames across 8×6 local tiles"
                    },
                    checked = !isPixelServicesLocked && hdrConfig.subPixelAlignment,
                    position = ExpressiveListPosition.MIDDLE,
                    badgeShape = ExpressiveButtonShape.CIRCLE_TO_SQUARE,
                    enabled = !isPixelServicesLocked,
                    onCheckedChange = { enabled ->
                        onUpdatePrefs { it.copy(hdrConfig = it.hdrConfig.copy(subPixelAlignment = enabled)) }
                    },
                )
            }
            item(key = "row_advanced_toggle", contentType = "advanced_toggle") {
                val advEnabled = !isPixelServicesLocked
                val advInteraction = remember { MutableInteractionSource() }
                val advPressed by advInteraction.collectIsPressedAsState()
                val advScaleState = animateFloatAsState(
                    targetValue = if (advEnabled && advPressed) 0.985f else 1f,
                    animationSpec = M3ExpressiveMotion.fastSpatial(),
                    label = "adv_row_scale",
                )
                val chevronRotationState = animateFloatAsState(
                    targetValue = if (effectiveAdvancedExpanded) 180f else 0f,
                    animationSpec = M3ExpressiveMotion.defaultSpatial(),
                    label = "adv_chevron_rot",
                )

                Surface(
                    shape = expressiveSegmentedCardShape(
                        if (effectiveAdvancedExpanded) ExpressiveListPosition.MIDDLE else ExpressiveListPosition.BOTTOM
                    ),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            val s = advScaleState.value
                            scaleX = s
                            scaleY = s
                            alpha = if (advEnabled) 1f else 0.55f
                        },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                enabled = advEnabled,
                                interactionSource = advInteraction,
                                indication = null,
                            ) {
                                haptics.tick()
                                advancedExpanded = !advancedExpanded
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .testTag("settings_advanced_hdr_row"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsIconBadge(
                            icon = Icons.Rounded.Tune,
                            highlighted = effectiveAdvancedExpanded,
                            shapeStyle = ExpressiveButtonShape.CIRCLE_TO_CLOVER,
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Advanced HDR+ tuning",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (isPixelServicesLocked) {
                                    "Managed by Pixel Camera Services"
                                } else {
                                    "Highlight roll-off, tone curve, denoise & sharpening"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.graphicsLayer { rotationZ = chevronRotationState.value },
                        )
                    }
                }
            }

            if (effectiveAdvancedExpanded) {
                item(key = "row_advanced_sliders", contentType = "sliders") {
                    Surface(
                        shape = expressiveSegmentedCardShape(ExpressiveListPosition.BOTTOM),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            PixelSettingsSlider(
                                title = "Highlight recovery",
                                value = hdrConfig.highlightRecovery,
                                onValueChange = { v ->
                                    onUpdatePrefs { it.copy(hdrConfig = it.hdrConfig.copy(highlightRecovery = v)) }
                                },
                            )
                            PixelSettingsSlider(
                                title = "Local tone mapping",
                                value = hdrConfig.localToneMapping,
                                onValueChange = { v ->
                                    onUpdatePrefs { it.copy(hdrConfig = it.hdrConfig.copy(localToneMapping = v)) }
                                },
                            )
                            PixelSettingsSlider(
                                title = "Temporal shadow denoise",
                                value = hdrConfig.temporalDenoise,
                                onValueChange = { v ->
                                    onUpdatePrefs { it.copy(hdrConfig = it.hdrConfig.copy(temporalDenoise = v)) }
                                },
                            )
                            PixelSettingsSlider(
                                title = "Sabre deconvolution sharpening",
                                value = hdrConfig.sabreSharpening,
                                onValueChange = { v ->
                                    onUpdatePrefs { it.copy(hdrConfig = it.hdrConfig.copy(sabreSharpening = v)) }
                                },
                            )
                        }
                    }
                }
            }

            // Section: Viewfinder HUD
            item(key = "header_hud", contentType = "header") {
                SectionHeader("Viewfinder HUD")
            }
            val hudAnalysisAvailable = isAnalysisActiveInSession && !isPixelServicesLocked
            item(key = "row_histogram", contentType = "switch") {
                PixelSwitchRow(
                    icon = Icons.Rounded.Equalizer,
                    title = "Live RGB + Luma histogram",
                    subtitle = if (hudAnalysisAvailable) {
                        "Display live tonal distribution on viewfinder"
                    } else {
                        "Unavailable while Pixel Camera Services is active"
                    },
                    checked = hudAnalysisAvailable && showHistogram,
                    position = ExpressiveListPosition.TOP,
                    badgeShape = ExpressiveButtonShape.CIRCLE_TO_COOKIE,
                    enabled = hudAnalysisAvailable,
                    onCheckedChange = { v -> onUpdatePrefs { it.copy(showHistogram = v) } },
                )
            }
            item(key = "row_zebra", contentType = "switch") {
                PixelSwitchRow(
                    icon = Icons.Rounded.WbSunny,
                    title = "Highlight clipping indicator",
                    subtitle = if (hudAnalysisAvailable) {
                        "Warn when highlights exceed 97% luminance"
                    } else {
                        "Unavailable while Pixel Camera Services is active"
                    },
                    checked = hudAnalysisAvailable && showZebraHighlights,
                    position = ExpressiveListPosition.MIDDLE,
                    badgeShape = ExpressiveButtonShape.CIRCLE_TO_BURST,
                    accentContainer = MaterialTheme.colorScheme.secondaryContainer,
                    accentContent = MaterialTheme.colorScheme.onSecondaryContainer,
                    enabled = hudAnalysisAvailable,
                    onCheckedChange = { v -> onUpdatePrefs { it.copy(showZebraHighlights = v) } },
                )
            }
            item(key = "row_peaking", contentType = "switch") {
                PixelSwitchRow(
                    icon = Icons.Rounded.AutoFixHigh,
                    title = "Focus edge sharpness readout",
                    subtitle = if (hudAnalysisAvailable) {
                        "Show live Laplacian focus score badge"
                    } else {
                        "Unavailable while Pixel Camera Services is active"
                    },
                    checked = hudAnalysisAvailable && showFocusPeaking,
                    position = ExpressiveListPosition.BOTTOM,
                    badgeShape = ExpressiveButtonShape.CIRCLE_TO_CLOVER,
                    accentContainer = MaterialTheme.colorScheme.tertiaryContainer,
                    accentContent = MaterialTheme.colorScheme.onTertiaryContainer,
                    enabled = hudAnalysisAvailable,
                    onCheckedChange = { v -> onUpdatePrefs { it.copy(showFocusPeaking = v) } },
                )
            }
        }
    }

    if (showGridDialog) {
        PixelOptionDialog(
            title = "Grid type",
            options = GridOverlayType.entries.map { it to it.label },
            selected = gridOverlay,
            onSelect = { grid ->
                onUpdatePrefs { it.copy(gridOverlay = grid) }
                showGridDialog = false
            },
            onDismiss = { showGridDialog = false },
        )
    }

    if (showAspectDialog) {
        PixelOptionDialog(
            title = "Camera photo resolution aspect",
            options = AspectRatioMode.entries.map {
                it to if (it == AspectRatioMode.RATIO_4_3) "Full sensor (4:3)" else "Wide crop (16:9)"
            },
            selected = aspectRatio,
            onSelect = { ratio ->
                onUpdatePrefs { it.copy(aspectRatio = ratio) }
                showAspectDialog = false
            },
            onDismiss = { showAspectDialog = false },
        )
    }

    if (showNightSightDialog) {
        PixelOptionDialog(
            title = "Low Light / Night Sight",
            options = listOf(
                NightSightMode.AUTO to "Auto (Recommended — detects low light)",
                NightSightMode.ON to "Always On (Force long exposure)",
                NightSightMode.OFF to "Off",
            ),
            selected = nightSightMode,
            onSelect = { mode ->
                onUpdatePrefs { it.copy(nightSightMode = mode) }
                showNightSightDialog = false
            },
            onDismiss = { showNightSightDialog = false },
        )
    }

    if (showHdrModeDialog) {
        PixelOptionDialog(
            title = "HDR+ control",
            options = HdrMode.entries.map { it to it.label },
            selected = hdrMode,
            onSelect = { mode ->
                onUpdatePrefs { it.copy(hdrMode = mode) }
                showHdrModeDialog = false
            },
            onDismiss = { showHdrModeDialog = false },
        )
    }

    if (showBurstDialog) {
        PixelOptionDialog(
            title = "HDR+ burst frames",
            options = listOf(2 to "2 Frames (Fastest)", 3 to "3 Frames (Balanced)", 5 to "5 Frames (Max DR)"),
            selected = hdrConfig.burstFrameCount,
            onSelect = { cnt ->
                onUpdatePrefs { it.copy(hdrConfig = it.hdrConfig.copy(burstFrameCount = cnt)) }
                showBurstDialog = false
            },
            onDismiss = { showBurstDialog = false },
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    topSpacing: Boolean = true,
) {
    Column {
        if (topSpacing) Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun SettingsIconBadge(
    icon: ImageVector,
    highlighted: Boolean,
    shapeStyle: ExpressiveButtonShape = ExpressiveButtonShape.CIRCLE_TO_COOKIE,
    accentContainer: Color = MaterialTheme.colorScheme.primaryContainer,
    accentContent: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    val bgState = animateColorAsState(
        targetValue = if (highlighted) accentContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = M3ExpressiveMotion.fastEffects(),
        label = "settings_badge_bg",
    )
    val fg by animateColorAsState(
        targetValue = if (highlighted) accentContent else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = M3ExpressiveMotion.fastEffects(),
        label = "settings_badge_fg",
    )

    val cornerInt = if (shapeStyle == ExpressiveButtonShape.CIRCLE_TO_SQUARE ||
        shapeStyle == ExpressiveButtonShape.SQUARE_TO_COOKIE
    ) {
        val c by animateFloatAsState(
            targetValue = if (highlighted) 30f else 50f,
            animationSpec = M3ExpressiveMotion.fastSpatial(),
            label = "settings_badge_corner",
        )
        c.toInt().coerceIn(24, 50)
    } else {
        50
    }

    val quantAmp = if (shapeStyle != ExpressiveButtonShape.CIRCLE_TO_SQUARE) {
        val amp by animateFloatAsState(
            targetValue = when (shapeStyle) {
                ExpressiveButtonShape.CIRCLE_TO_COOKIE -> if (highlighted) 0.095f else 0f
                ExpressiveButtonShape.CIRCLE_TO_CLOVER -> if (highlighted) 0.14f else 0f
                ExpressiveButtonShape.CIRCLE_TO_BURST -> if (highlighted) 0.11f else 0f
                ExpressiveButtonShape.COOKIE_PERSISTENT -> if (highlighted) 0.10f else 0.075f
                else -> 0f
            },
            animationSpec = M3ExpressiveMotion.fastSpatial(),
            label = "settings_badge_amp",
        )
        (amp * 100f).toInt() / 100f
    } else {
        0f
    }

    Box(
        modifier = Modifier
            .size(40.dp)
            .drawExpressiveButtonBackground(
                shapeStyle = shapeStyle,
                quantAmp = quantAmp,
                cornerPercent = cornerInt,
                colorProvider = { bgState.value },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun PixelSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    position: ExpressiveListPosition,
    badgeShape: ExpressiveButtonShape,
    accentContainer: Color = MaterialTheme.colorScheme.primaryContainer,
    accentContent: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    enabled: Boolean = true,
    rowTestTag: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptics = rememberCameraHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scaleState = animateFloatAsState(
        targetValue = if (enabled && isPressed) 0.985f else 1f,
        animationSpec = M3ExpressiveMotion.fastSpatial(),
        label = "switch_row_scale",
    )

    Surface(
        shape = expressiveSegmentedCardShape(position),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val s = scaleState.value
                scaleX = s
                scaleY = s
                alpha = if (enabled) 1f else 0.55f
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (rowTestTag != null) Modifier.testTag(rowTestTag) else Modifier)
                .clickable(
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = null,
                ) {
                    haptics.tick()
                    onCheckedChange(!checked)
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsIconBadge(
                icon = icon,
                highlighted = enabled && checked,
                shapeStyle = badgeShape,
                accentContainer = accentContainer,
                accentContent = accentContent,
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ExpressiveSwitch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

@Composable
private fun PixelPickerRow(
    icon: ImageVector,
    title: String,
    valueLabel: String,
    position: ExpressiveListPosition,
    badgeShape: ExpressiveButtonShape,
    accentContainer: Color = MaterialTheme.colorScheme.secondaryContainer,
    accentContent: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val haptics = rememberCameraHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scaleState = animateFloatAsState(
        targetValue = if (enabled && isPressed) 0.985f else 1f,
        animationSpec = M3ExpressiveMotion.fastSpatial(),
        label = "picker_row_scale",
    )

    Surface(
        shape = expressiveSegmentedCardShape(position),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val s = scaleState.value
                scaleX = s
                scaleY = s
                alpha = if (enabled) 1f else 0.55f
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = null,
                ) {
                    haptics.tick()
                    onClick()
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsIconBadge(
                icon = icon,
                highlighted = enabled,
                shapeStyle = badgeShape,
                accentContainer = accentContainer,
                accentContent = accentContent,
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PixelSettingsSlider(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    ExpressiveSlider(
        value = value.coerceIn(0f, 1f),
        onValueChange = onValueChange,
        valueRange = 0f..1f,
        label = title,
        valueText = "${(value * 100f).roundToInt()}%",
        accentColor = MaterialTheme.colorScheme.primary,
        showCenterDetent = false,
        hapticStep = 0.08f,
    )
}

@Composable
private fun <T> PixelOptionDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberCameraHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        title = {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                options.forEachIndexed { idx, (item, label) ->
                    val isSelected = item == selected
                    val pos = when {
                        options.size == 1 -> ExpressiveListPosition.SINGLE
                        idx == 0 -> ExpressiveListPosition.TOP
                        idx == options.lastIndex -> ExpressiveListPosition.BOTTOM
                        else -> ExpressiveListPosition.MIDDLE
                    }
                    val bg by animateColorAsState(
                        targetValue = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        animationSpec = M3ExpressiveMotion.fastEffects(),
                        label = "option_row_bg",
                    )
                    val rowShape = if (isSelected) {
                        RoundedCornerShape(22.dp)
                    } else {
                        expressiveSegmentedCardShape(pos, outerCorner = 20.dp, innerCorner = 6.dp)
                    }
                    Surface(
                        onClick = {
                            haptics.tick()
                            onSelect(item)
                        },
                        shape = rowShape,
                        color = bg,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    haptics.tick()
                                    onSelect(item)
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.primary,
                                    unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.primary)
            }
        },
    )
}
