package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.camera.model.CapturedPhotoRecord
import com.example.ui.theme.FocusGreen
import com.example.ui.theme.PixelAmber

@Composable
fun PhotoInspectorScreen(
    photos: List<CapturedPhotoRecord>,
    selectedIndex: Int,
    fallbackUri: String?,
    onSelectIndex: (Int) -> Unit,
    onDelete: (CapturedPhotoRecord) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler { onClose() }

    val haptics = rememberCameraHaptics()
    val context = LocalContext.current
    val activeRecord = photos.getOrNull(selectedIndex)
    val displayUri = activeRecord?.uriString ?: fallbackUri

    // Enable COLOR_MODE_HDR only while inspecting an Ultra HDR photo (API 34+)
    val wantsUltraHdrDisplay = (activeRecord?.isUltraHdr == true) &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    DisposableEffect(wantsUltraHdrDisplay, context) {
        val activity = context.findActivity()
        if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.window.colorMode = if (wantsUltraHdrDisplay) {
                ActivityInfo.COLOR_MODE_HDR
            } else {
                ActivityInfo.COLOR_MODE_DEFAULT
            }
        }
        onDispose {
            if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Material You 3 Expressive Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExpressiveIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.cd_close),
                    shapeStyle = ExpressiveButtonShape.CIRCLE_TO_SQUARE,
                    size = 44.dp,
                    iconSize = 22.dp,
                    onClick = onClose,
                    testTag = "inspector_back_button",
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.gallery_inspector_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (displayUri != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExpressiveIconButton(
                        icon = Icons.Rounded.Share,
                        contentDescription = stringResource(R.string.cd_share_photo),
                        shapeStyle = ExpressiveButtonShape.CIRCLE_TO_CLOVER,
                        size = 42.dp,
                        iconSize = 20.dp,
                        active = true,
                        activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        hapticStyle = ExpressiveHaptic.ACTION,
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/jpeg"
                                putExtra(Intent.EXTRA_STREAM, Uri.parse(displayUri))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share HDR+ Photo"))
                        },
                        testTag = "inspector_share_button",
                    )

                    if (activeRecord != null) {
                        ExpressiveIconButton(
                            icon = Icons.Rounded.DeleteOutline,
                            contentDescription = stringResource(R.string.cd_delete_photo),
                            shapeStyle = ExpressiveButtonShape.CIRCLE_TO_COOKIE,
                            size = 42.dp,
                            iconSize = 20.dp,
                            active = true,
                            activeContainerColor = MaterialTheme.colorScheme.errorContainer,
                            activeContentColor = MaterialTheme.colorScheme.onErrorContainer,
                            hapticStyle = ExpressiveHaptic.ACTION,
                            onClick = { onDelete(activeRecord) },
                            testTag = "inspector_delete_button",
                        )
                    }
                }
            }
        }

        if (displayUri == null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = ExpressiveShapes.Cookie9,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                        modifier = Modifier.size(80.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.PhotoLibrary,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.no_photos_captured),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                AnimatedContent(
                    targetState = displayUri,
                    transitionSpec = {
                        (fadeIn(M3ExpressiveMotion.fastEffects()) +
                            scaleIn(initialScale = 0.96f, animationSpec = M3ExpressiveMotion.defaultSpatial())) togetherWith
                            (fadeOut(M3ExpressiveMotion.fastEffects()) + scaleOut(targetScale = 1.02f))
                    },
                    label = "inspector_photo_swap",
                ) { uri ->
                    AsyncImage(
                        model = Uri.parse(uri),
                        contentDescription = activeRecord?.displayName ?: "Captured photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (photos.size > 1) {
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(photos, key = { _, item -> item.uriString }) { idx, item ->
                        val isSelected = idx == selectedIndex
                        val interactionSource = remember { MutableInteractionSource() }
                        val isPressed by interactionSource.collectIsPressedAsState()
                        val thumbScale by animateFloatAsState(
                            targetValue = when {
                                isPressed -> 0.88f
                                isSelected -> 1.06f
                                else -> 0.94f
                            },
                            animationSpec = M3ExpressiveMotion.fastSpatial(),
                            label = "filmstrip_scale",
                        )
                        val thumbCornerPercent by animateFloatAsState(
                            targetValue = if (isSelected) 50f else 22f,
                            animationSpec = M3ExpressiveMotion.fastSpatial(),
                            label = "filmstrip_corner",
                        )

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Surface(
                                onClick = {
                                    haptics.tick()
                                    onSelectIndex(idx)
                                },
                                interactionSource = interactionSource,
                                shape = RoundedCornerShape(percent = thumbCornerPercent.toInt().coerceIn(20, 50)),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp,
                                modifier = Modifier
                                    .size(56.dp)
                                    .graphicsLayer {
                                        scaleX = thumbScale
                                        scaleY = thumbScale
                                    },
                            ) {
                                AsyncImage(
                                    model = Uri.parse(item.uriString),
                                    contentDescription = item.displayName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(width = if (isSelected) 16.dp else 0.dp, height = 4.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                }
            }

            if (activeRecord != null) {
                Spacer(modifier = Modifier.height(12.dp))
                ExpressiveSurfacePanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = if (activeRecord.isNightSight) {
                                    "Night Sight • ${activeRecord.hdrMode.badge}"
                                } else {
                                    "${activeRecord.mode.label} • ${activeRecord.hdrMode.badge}"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = if (activeRecord.isNightSight) PixelAmber else MaterialTheme.colorScheme.tertiary,
                            )
                            Text(
                                text = "Look: ${activeRecord.lookName} • ${activeRecord.width}×${activeRecord.height}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (activeRecord.isNightSight) {
                                Surface(
                                    shape = CircleShape,
                                    color = PixelAmber.copy(alpha = 0.18f),
                                ) {
                                    Text(
                                        text = "Night",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = PixelAmber,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    )
                                }
                            }
                            if (activeRecord.isUltraHdr) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                ) {
                                    Text(
                                        text = "Ultra HDR",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    )
                                }
                            }
                            Surface(
                                shape = CircleShape,
                                color = FocusGreen.copy(alpha = 0.16f),
                            ) {
                                Text(
                                    text = "+${String.format("%.1f", activeRecord.metrics.dynamicRangeGainStops)} EV DR",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = FocusGreen,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TelemetryChip(
                            label = "MERGED",
                            value = "${activeRecord.metrics.framesMerged} frames",
                            modifier = Modifier.weight(1f),
                        )
                        TelemetryChip(
                            label = "ALIGN",
                            value = "${(activeRecord.metrics.alignmentConfidence * 100).toInt()}%",
                            modifier = Modifier.weight(1f),
                        )
                        TelemetryChip(
                            label = "COMPUTE",
                            value = "${activeRecord.metrics.processingTimeMs} ms",
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TelemetryChip(
                            label = "ISO",
                            value = activeRecord.isoUsed.toString(),
                            accent = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.weight(1f),
                        )
                        TelemetryChip(
                            label = "SHUTTER",
                            value = activeRecord.shutterUsedLabel,
                            accent = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.weight(1f),
                        )
                        TelemetryChip(
                            label = "WB",
                            value = "${activeRecord.wbKelvinUsed}K",
                            modifier = Modifier.weight(1f),
                        )
                        TelemetryChip(
                            label = "EV",
                            value = String.format("%+.1f", activeRecord.evUsed),
                            modifier = Modifier.weight(1f),
                        )
                    }

                    if (activeRecord.lumaHistogram.size >= 2) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val histColor = MaterialTheme.colorScheme.primary
                        val histFillPath = remember { Path() }
                        val histLinePath = remember { Path() }
                        val histBrush = remember(histColor) {
                            Brush.verticalGradient(listOf(histColor.copy(alpha = 0.42f), Color.Transparent))
                        }
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .graphicsLayer {
                                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                                }
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        ) {
                            val bins = activeRecord.lumaHistogram
                            val stepX = size.width / (bins.size - 1)
                            histFillPath.reset()
                            histLinePath.reset()
                            histFillPath.moveTo(0f, size.height)
                            bins.forEachIndexed { idx, v ->
                                val x = idx * stepX
                                val y = size.height - (v.coerceIn(0, 100) / 100f) * size.height
                                if (idx == 0) {
                                    histLinePath.moveTo(x, y)
                                    histFillPath.lineTo(x, y)
                                } else {
                                    histLinePath.lineTo(x, y)
                                    histFillPath.lineTo(x, y)
                                }
                            }
                            histFillPath.lineTo(size.width, size.height)
                            histFillPath.close()
                            drawPath(histFillPath, histBrush)
                            drawPath(histLinePath, histColor, style = Stroke(width = 1.6.dp.toPx()))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TelemetryChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
