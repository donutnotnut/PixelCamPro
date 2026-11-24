package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BedtimeOff
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.HdrEnhancedSelect
import androidx.compose.material.icons.rounded.HdrOff
import androidx.compose.material.icons.rounded.HdrOn
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tonality
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.R
import com.example.camera.model.AspectRatioMode
import com.example.camera.model.FlashMode
import com.example.camera.model.HdrMode
import com.example.camera.model.LensOption
import com.example.camera.model.LowLightDetector
import com.example.camera.model.NightSightMode
import com.example.camera.model.PixelExtensionMode
import com.example.camera.model.TimerDuration
import com.example.camera.model.ToneColorMath
import com.example.camera.model.ToneShaderMath
import com.example.ui.components.CompositionGridOverlay
import com.example.ui.components.ExpressiveButtonShape
import com.example.ui.components.ExpressiveFilledButton
import com.example.ui.components.ExpressiveFloatingToolbar
import com.example.ui.components.ExpressiveHaptic
import com.example.ui.components.ExpressiveIconButton
import com.example.ui.components.ExpressiveLoadingIndicator
import com.example.ui.components.ExpressiveOutlinedButton
import com.example.ui.components.ExpressiveSegmentedGroup
import com.example.ui.components.ExpressiveShapes
import com.example.ui.components.ExpressiveSlider
import com.example.ui.components.ExpressiveSurfacePanel
import com.example.ui.components.FocusReticleOverlay
import com.example.ui.components.HorizonLevelOverlay
import com.example.ui.components.LiveHistogramWidget
import com.example.ui.components.M3ExpressiveMotion
import com.example.ui.components.PhotoInspectorScreen
import com.example.ui.components.PixelLooksBar
import com.example.ui.components.PixelSettingsSheet
import com.example.ui.components.ProControlsPanel
import com.example.ui.components.SaveTonePresetDialog
import com.example.ui.components.ToneAdjustmentsPanel
import com.example.ui.components.drawExpressiveShapeBackground
import com.example.ui.components.rememberCameraHaptics
import com.example.ui.theme.PixelAmber
import com.example.ui.theme.ViewfinderBackdrop
import com.example.ui.viewmodel.CameraUiState
import com.example.ui.viewmodel.CameraViewModel
import kotlinx.coroutines.delay
import kotlin.math.abs

private val PixelBlack = Color(0xFF000000)

@Composable
fun CameraRoute(
    viewModel: CameraViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequestedOnce by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        permissionRequestedOnce = true
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCameraPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!hasCameraPermission) {
        CameraPermissionOnboarding(
            showSettingsButton = permissionRequestedOnce,
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                )
                context.startActivity(intent)
            },
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PixelBlack),
    ) {
        CameraScreenContent(
            uiState = uiState,
            viewModel = viewModel,
        )

        AnimatedVisibility(
            visible = uiState.isSettingsSheetOpen,
            enter = slideInVertically(tween(210, easing = FastOutSlowInEasing)) { it / 4 },
            exit = slideOutVertically(tween(170, easing = FastOutSlowInEasing)) { it / 4 },
        ) {
            PixelSettingsSheet(
                showHorizonLevel = uiState.showHorizonLevel,
                gridOverlay = uiState.gridOverlay,
                aspectRatio = uiState.aspectRatio,
                hdrMode = uiState.hdrMode,
                nightSightMode = uiState.nightSightMode,
                isNightSightActive = uiState.isNightSightActive,
                pixelCameraServicesEnabled = uiState.pixelCameraServicesEnabled,
                pixelExtensionsSupported = uiState.pixelExtensionsSupported,
                isPixelNightOnlyDevice = uiState.isPixelNightOnlyDevice,
                hasCheckedPixelExtensions = uiState.hasCheckedPixelExtensions,
                activePixelExtensionMode = uiState.activePixelExtensionMode,
                isPixelServicesLocked = uiState.isPixelServicesLocked,
                isAnalysisActiveInSession = uiState.isAnalysisActiveInSession,
                nativeUltraHdrSupported = uiState.nativeUltraHdrSupported,
                nativeUltraHdrActive = uiState.nativeUltraHdrActive,
                hdrConfig = uiState.hdrConfig,
                showHistogram = uiState.showHistogram,
                showZebraHighlights = uiState.showZebraHighlights,
                showFocusPeaking = uiState.showFocusPeaking,
                onDismiss = { viewModel.setSettingsSheetOpen(false) },
                onTogglePixelCameraServices = viewModel::setPixelCameraServicesEnabled,
                onUpdatePrefs = viewModel::updatePreferences,
            )
        }

        AnimatedVisibility(
            visible = uiState.isInspectorOpen,
            enter = slideInVertically(tween(210, easing = FastOutSlowInEasing)) { it / 5 },
            exit = slideOutVertically(tween(170, easing = FastOutSlowInEasing)) { it / 5 },
        ) {
            PhotoInspectorScreen(
                photos = uiState.capturedPhotos,
                selectedIndex = uiState.selectedPhotoIndex,
                fallbackUri = uiState.latestGalleryThumbnailUri,
                onSelectIndex = viewModel::selectCapturedPhoto,
                onDelete = viewModel::deleteCapturedPhoto,
                onClose = { viewModel.setInspectorOpen(false) },
            )
        }
    }
}

@Composable
private fun CameraScreenContent(
    uiState: CameraUiState,
    viewModel: CameraViewModel,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val haptics = rememberCameraHaptics()
    val cornerRadius43Px = remember(context) { 26f * context.resources.displayMetrics.density }
    val previewStateHolder = remember {
        object {
            var activePaint: Paint? = null
            var cornerRadiusPx: Float = cornerRadius43Px
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // Keep PreviewView itself off redundant offscreen layers while its child TextureView stays GPU-backed
            setLayerType(View.LAYER_TYPE_NONE, null)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, previewStateHolder.cornerRadiusPx)
                }
            }
            setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) {
                    if (child is TextureView) {
                        val paint = previewStateHolder.activePaint
                        if (paint != null) {
                            child.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
                        } else {
                            child.setLayerType(View.LAYER_TYPE_NONE, null)
                        }
                    }
                }
                override fun onChildViewRemoved(parent: View?, child: View?) = Unit
            })
        }
    }
    val runtimeShader = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { RuntimeShader(ToneShaderMath.AGSL_TONE_SHADER) }.getOrNull()
        } else null
    }

    LaunchedEffect(uiState.aspectRatio) {
        previewStateHolder.cornerRadiusPx =
            if (uiState.aspectRatio == AspectRatioMode.RATIO_16_9) 0f else cornerRadius43Px
        previewView.invalidateOutline()
    }

    // Rebind CameraX whenever lens facing, discrete physical camera ID, aspect ratio,
    // Pixel Camera Services toggle, target Extension mode, or native Ultra HDR capture format changes
    LaunchedEffect(
        uiState.lensFacing,
        uiState.preferredCameraId,
        uiState.aspectRatio,
        uiState.pixelCameraServicesEnabled,
        uiState.desiredPixelExtensionMode,
        uiState.wantsNativeUltraHdrCapture,
    ) {
        viewModel.bindCamera(lifecycleOwner, previewView)
    }

    // Keep PreviewView permanently VISIBLE behind full-screen Settings/PhotoInspector so TextureView
    // retains a single stable Surface across camera rebinds and never triggers Surface::createEvilTwin.
    // Heavy preview shaders, ImageAnalysis, and sensors still pause while an overlay is open.
    val isFullScreenOverlayOpen = uiState.isSettingsSheetOpen || uiState.isInspectorOpen

    // Zero-FBO hardware TextureLayer paint for Cinematic / Contrast / Vibrancy / EV / WB (120fps!),
    // escalating to FP16 AGSL RuntimeShader RenderEffect ONLY when non-linear Shadows/Highlights sliders are active.
    LaunchedEffect(
        isFullScreenOverlayOpen,
        uiState.isQuickSettingsExpanded,
        uiState.manualSettings,
        uiState.hdrConfig.cinematicEffectEnabled,
        uiState.hdrConfig.highlightRecovery,
        uiState.effectiveHdrMode,
        uiState.telemetry.manualControlHardwareSupported,
    ) {
        val childTextureView = (0 until previewView.childCount)
            .map { previewView.getChildAt(it) }
            .filterIsInstance<TextureView>()
            .firstOrNull()

        if (isFullScreenOverlayOpen ||
            ToneShaderMath.isNeutral(
                manual = uiState.manualSettings,
                hdrMode = uiState.effectiveHdrMode,
                config = uiState.hdrConfig,
                hardwareManualSupported = uiState.telemetry.manualControlHardwareSupported,
            )
        ) {
            previewStateHolder.activePaint = null
            childTextureView?.setLayerType(View.LAYER_TYPE_NONE, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                previewView.setRenderEffect(null)
            }
        } else if (!uiState.isQuickSettingsExpanded &&
            ToneShaderMath.needsNonLinearShader(uiState.manualSettings) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runtimeShader != null
        ) {
            previewStateHolder.activePaint = null
            childTextureView?.setLayerType(View.LAYER_TYPE_NONE, null)
            ToneShaderMath.applyUniforms(
                shader = runtimeShader,
                manual = uiState.manualSettings,
                hdrMode = uiState.effectiveHdrMode,
                config = uiState.hdrConfig,
                hardwareManualSupported = uiState.telemetry.manualControlHardwareSupported,
            )
            previewView.setRenderEffect(
                RenderEffect.createRuntimeShaderEffect(
                    runtimeShader,
                    ToneShaderMath.INPUT_SHADER_UNIFORM,
                )
            )
        } else {
            // Fast path: single-pass GPU ColorMatrix directly on TextureView's hardware layer (ZERO offscreen FBO!)
            val evVisualDelta = if (uiState.manualSettings.isManualExposureActive) {
                uiState.manualSettings.exposureCompensationEv * 0.7f
            } else {
                uiState.manualSettings.exposureCompensationEv * 0.25f
            }
            val matrix = ToneColorMath.buildPreviewColorMatrix(
                computationalEvDelta = evVisualDelta,
                shadowSlider = uiState.manualSettings.shadowBoost,
                highlightSlider = uiState.manualSettings.highlightBoost,
                contrastSlider = uiState.manualSettings.contrastAdjust,
                vibrancySlider = uiState.manualSettings.vibrancyAdjust,
                kelvinOverride = if (!uiState.telemetry.manualControlHardwareSupported) {
                    uiState.manualSettings.whiteBalanceKelvin
                } else null,
                cinematicEffect = uiState.hdrConfig.cinematicEffectEnabled,
            )
            val freshPaint = Paint().apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            }
            previewStateHolder.activePaint = freshPaint
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                previewView.setRenderEffect(null)
            }
            childTextureView?.setLayerType(View.LAYER_TYPE_HARDWARE, freshPaint)
        }
    }

    var showShutterFlash by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.shutterFlashTick) {
        if (uiState.shutterFlashTick > 0L) {
            showShutterFlash = true
            delay(110L)
            showShutterFlash = false
        }
    }

    var showSaveLookDialogFromBar by remember { mutableStateOf(false) }
    var viewfinderSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PixelBlack),
    ) {
        // Layer 1 (Back): Full-screen viewfinder container with hardware ViewOutlineProvider corners
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
        ) {
            val isPortrait = maxHeight >= maxWidth
            val is16by9 = uiState.aspectRatio == AspectRatioMode.RATIO_16_9
            val targetAspect = when (uiState.aspectRatio) {
                AspectRatioMode.RATIO_4_3 -> if (isPortrait) 3f / 4f else 4f / 3f
                AspectRatioMode.RATIO_16_9 -> if (isPortrait) 9f / 16f else 16f / 9f
            }
            val animatedAspect by animateFloatAsState(
                targetValue = targetAspect,
                animationSpec = M3ExpressiveMotion.slowSpatial(),
                label = "vf_aspect_ratio",
            )
            val reserveTopFor43 = !is16by9 && isPortrait && maxHeight >= 760.dp
            val vfTopPad by animateDpAsState(
                targetValue = if (reserveTopFor43) 64.dp else 0.dp,
                animationSpec = M3ExpressiveMotion.slowSpatial(),
                label = "vf_top_pad",
            )
            val vfAlignment = if (reserveTopFor43) Alignment.TopCenter else Alignment.Center

            Box(
                modifier = Modifier
                    .align(vfAlignment)
                    .then(
                        if (reserveTopFor43) {
                            Modifier
                                .statusBarsPadding()
                                .padding(top = vfTopPad)
                        } else {
                            Modifier
                        }
                    )
                    .fillMaxWidth()
                    .widthIn(max = if (isPortrait) 680.dp else 960.dp)
                    .aspectRatio(animatedAspect, matchHeightConstraintsFirst = true)
                    .background(ViewfinderBackdrop)
                    .onSizeChanged { viewfinderSize = it }
                    // Unified pinch-to-zoom + single-tap focus + long-press AE/AF lock gesture
                    .pointerInput(uiState.telemetry.minZoomRatio, uiState.telemetry.maxZoomRatio) {
                        val touchSlopSq = viewConfiguration.touchSlop * viewConfiguration.touchSlop
                        val longPressMs = viewConfiguration.longPressTimeoutMillis.coerceIn(380L, 650L)
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            val downPos = down.position
                            val downTimeMs = System.currentTimeMillis()
                            var wasMultiTouch = false
                            var movedBeyondSlop = false
                            var longPressTriggered = false
                            var lastUnconsumedUp: androidx.compose.ui.geometry.Offset? = null

                            while (true) {
                                val event = if (!wasMultiTouch && !movedBeyondSlop && !longPressTriggered) {
                                    val elapsed = System.currentTimeMillis() - downTimeMs
                                    val remaining = (longPressMs - elapsed).coerceAtLeast(1L)
                                    withTimeoutOrNull(remaining) { awaitPointerEvent() }
                                } else {
                                    awaitPointerEvent()
                                }

                                if (event == null) {
                                    if (!wasMultiTouch && !movedBeyondSlop && !longPressTriggered &&
                                        !down.isConsumed && viewfinderSize.width > 0 && viewfinderSize.height > 0
                                    ) {
                                        longPressTriggered = true
                                        haptics.aeAfLock()
                                        viewModel.onLongPressViewfinder(
                                            previewView = previewView,
                                            xPx = downPos.x,
                                            yPx = downPos.y,
                                            viewWidth = viewfinderSize.width.toFloat(),
                                            viewHeight = viewfinderSize.height.toFloat(),
                                        )
                                    }
                                    continue
                                }

                                val pressedCount = event.changes.count { it.pressed }
                                if (pressedCount >= 2) {
                                    wasMultiTouch = true
                                    val zoomChange = event.calculateZoom()
                                    if (abs(zoomChange - 1f) > 0.005f) {
                                        viewModel.onPinchZoom(zoomChange)
                                        event.changes.forEach { it.consume() }
                                    }
                                } else if (pressedCount == 1 && !wasMultiTouch) {
                                    val c = event.changes.firstOrNull { it.pressed }
                                    if (c != null) {
                                        if (c.isConsumed) {
                                            movedBeyondSlop = true
                                        } else {
                                            val dx = c.position.x - downPos.x
                                            val dy = c.position.y - downPos.y
                                            if (dx * dx + dy * dy > touchSlopSq) {
                                                movedBeyondSlop = true
                                            }
                                        }
                                    }
                                } else if (pressedCount == 0) {
                                    if (!wasMultiTouch && !movedBeyondSlop && !longPressTriggered && !down.isConsumed) {
                                        val upChange = event.changes.firstOrNull()
                                        if (upChange != null && !upChange.isConsumed) {
                                            lastUnconsumedUp = upChange.position
                                        }
                                    }
                                    break
                                }
                            }

                            val tapPos = lastUnconsumedUp
                            if (tapPos != null && viewfinderSize.width > 0 && viewfinderSize.height > 0) {
                                haptics.tick()
                                viewModel.onTapViewfinder(
                                    previewView = previewView,
                                    xPx = tapPos.x,
                                    yPx = tapPos.y,
                                    viewWidth = viewfinderSize.width.toFloat(),
                                    viewHeight = viewfinderSize.height.toFloat(),
                                )
                            }
                        }
                    },
            ) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize(),
                )

                CompositionGridOverlay(gridType = uiState.gridOverlay)

                androidx.compose.animation.AnimatedVisibility(
                    visible = uiState.showHorizonLevel,
                    enter = fadeIn(M3ExpressiveMotion.fastEffects()),
                    exit = fadeOut(M3ExpressiveMotion.fastEffects()),
                ) {
                    HorizonLevelOverlay(
                        horizonFlow = viewModel.horizonState,
                    )
                }

                FocusReticleOverlay(
                    reticle = uiState.focusReticle,
                    evValue = uiState.manualSettings.exposureCompensationEv,
                    onEvDragStart = viewModel::onReticleEvDragStart,
                    onEvChange = viewModel::onReticleEvChange,
                    onEvDragEnd = viewModel::onReticleEvDragEnd,
                    onUnlockAeAf = viewModel::unlockAeAf,
                )

                // Expressive bouncy countdown overlay
                androidx.compose.animation.AnimatedVisibility(
                    visible = uiState.countdownSecondsLeft > 0,
                    enter = fadeIn(M3ExpressiveMotion.fastEffects()),
                    exit = fadeOut(M3ExpressiveMotion.fastEffects()),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.36f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedContent(
                            targetState = uiState.countdownSecondsLeft,
                            transitionSpec = {
                                (scaleIn(initialScale = 1.45f, animationSpec = M3ExpressiveMotion.fastSpatial()) +
                                    fadeIn(M3ExpressiveMotion.fastEffects())) togetherWith
                                    (scaleOut(targetScale = 0.65f, animationSpec = M3ExpressiveMotion.fastEffects()) +
                                        fadeOut(M3ExpressiveMotion.fastEffects()))
                            },
                            label = "countdown_digit",
                        ) { sec ->
                            if (sec > 0) {
                                Text(
                                    text = sec.toString(),
                                    fontSize = 88.sp,
                                    style = MaterialTheme.typography.displaySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = showShutterFlash,
                    enter = fadeIn(M3ExpressiveMotion.fastEffects()),
                    exit = fadeOut(M3ExpressiveMotion.fastEffects()),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.45f)),
                    )
                }
            }
        }

        // Layer 2 (Front): Safe-area Material You 3 Expressive HUD, Top Bar, Lens Bar, Drawers & Shutter Deck
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PixelTopBar(
                uiState = uiState,
                onToggleQuickSettings = viewModel::toggleQuickSettings,
                onCycleFlash = viewModel::cycleFlashMode,
                onCycleNightSight = viewModel::cycleNightSightMode,
                onCycleTimer = viewModel::cycleTimerDuration,
                onCycleHdr = viewModel::cycleHdrMode,
                onToggleLooks = viewModel::toggleLooksBar,
                onToggleTone = viewModel::toggleTonePanel,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                // Active Pixel Camera Services badge on the viewfinder (tap to jump to Settings)
                androidx.compose.animation.AnimatedVisibility(
                    visible = uiState.isPixelServicesActive && !uiState.isQuickSettingsExpanded,
                    enter = fadeIn(M3ExpressiveMotion.fastEffects()) +
                        scaleIn(initialScale = 0.88f, animationSpec = M3ExpressiveMotion.fastSpatial()),
                    exit = fadeOut(M3ExpressiveMotion.fastEffects()) +
                        scaleOut(targetScale = 0.88f, animationSpec = M3ExpressiveMotion.fastEffects()),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 10.dp, start = 12.dp),
                ) {
                    val displayMode = if (uiState.activePixelExtensionMode != PixelExtensionMode.NONE) {
                        uiState.activePixelExtensionMode
                    } else {
                        uiState.desiredPixelExtensionMode
                    }
                    Surface(
                        onClick = {
                            haptics.tick()
                            viewModel.setSettingsSheetOpen(true)
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.90f),
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                        modifier = Modifier
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                CircleShape,
                            )
                            .semantics(mergeDescendants = true) {}
                            .testTag("pixel_services_viewfinder_badge"),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = displayMode.badge.ifBlank { "Pixel Services" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = uiState.showHistogram && uiState.isAnalysisActiveInSession && !uiState.isPixelServicesLocked,
                    enter = fadeIn(M3ExpressiveMotion.fastEffects()) +
                        scaleIn(initialScale = 0.88f, animationSpec = M3ExpressiveMotion.defaultSpatial()),
                    exit = fadeOut(M3ExpressiveMotion.fastEffects()) +
                        scaleOut(targetScale = 0.88f, animationSpec = M3ExpressiveMotion.fastEffects()),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 10.dp),
                ) {
                    LiveHistogramWidget(
                        histogramFlow = viewModel.histogramState,
                        showZebraWarning = uiState.showZebraHighlights,
                        showFocusPeakingBadge = uiState.showFocusPeaking,
                    )
                }

                // Material You 3 Expressive Quick Settings Drop-Down Panel (pure GPU slide+fade, zero relayout)
                androidx.compose.animation.AnimatedVisibility(
                    visible = uiState.isQuickSettingsExpanded,
                    enter = fadeIn(tween(130)) +
                        slideInVertically(tween(190, easing = FastOutSlowInEasing)) { -it / 4 },
                    exit = fadeOut(tween(110)) +
                        slideOutVertically(tween(150, easing = FastOutSlowInEasing)) { -it / 4 },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    PixelQuickSettingsDrawer(
                        uiState = uiState,
                        onSelectFlash = viewModel::setFlashMode,
                        onSelectNightSight = viewModel::setNightSightMode,
                        onSelectTimer = viewModel::setTimerDuration,
                        onSelectAspect = viewModel::setAspectRatio,
                        onSelectHdr = viewModel::setHdrMode,
                        onOpenMoreSettings = { viewModel.setSettingsSheetOpen(true) },
                    )
                }

                // Active Status / Burst Progress Banner
                val anyFloatingDrawerOpen = uiState.isLooksBarExpanded ||
                    uiState.isTonePanelExpanded ||
                    uiState.isProPanelExpanded
                val bannerText = when {
                    uiState.captureProgress.isCapturing -> uiState.captureProgress.statusText
                    uiState.captureProgress.savingCount > 0 -> uiState.captureProgress.statusText
                    else -> uiState.statusBannerMessage
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = bannerText != null && !anyFloatingDrawerOpen,
                    enter = fadeIn(tween(130)) +
                        slideInVertically(tween(160, easing = FastOutSlowInEasing)) { it / 2 },
                    exit = fadeOut(tween(110)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = if (uiState.isZoomRulerVisible) 118.dp else 66.dp),
                ) {
                    val isNightBanner = uiState.captureProgress.isCapturing && uiState.captureProgress.isNightSightCapture
                    Surface(
                        shape = CircleShape,
                        color = if (isNightBanner) {
                            PixelAmber.copy(alpha = 0.22f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                        modifier = if (isNightBanner) {
                            Modifier.border(1.dp, PixelAmber.copy(alpha = 0.6f), CircleShape)
                        } else Modifier,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 15.dp, vertical = 8.dp),
                        ) {
                            if (isNightBanner) {
                                Icon(
                                    imageVector = Icons.Rounded.NightsStay,
                                    contentDescription = null,
                                    tint = PixelAmber,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Text(
                                text = bannerText.orEmpty(),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isNightBanner) PixelAmber else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }

                // Pixel-style Auto Night Sight Indicator Pill (tap to pause/dismiss Night Sight)
                val showNightSightPill = uiState.isNightSightActive &&
                    bannerText == null &&
                    !anyFloatingDrawerOpen &&
                    !uiState.isZoomRulerVisible &&
                    !uiState.isQuickSettingsExpanded
                androidx.compose.animation.AnimatedVisibility(
                    visible = showNightSightPill,
                    enter = fadeIn(tween(160)) +
                        scaleIn(initialScale = 0.90f, animationSpec = M3ExpressiveMotion.fastSpatial()),
                    exit = fadeOut(tween(120)) +
                        scaleOut(targetScale = 0.92f, animationSpec = M3ExpressiveMotion.fastEffects()),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 66.dp),
                ) {
                    val estSec = uiState.estimatedNightHoldSeconds.coerceAtLeast(1.2f)
                    val holdTag = LowLightDetector.formatExposureSeconds(estSec)
                    val label = when {
                        uiState.isPixelServicesActive -> "Pixel Night • Tap to pause"
                        uiState.nightSightMode == NightSightMode.ON -> "Night Sight • $holdTag"
                        else -> "Low Light • $holdTag"
                    }
                    Surface(
                        onClick = {
                            haptics.tick()
                            viewModel.toggleNightSightQuick()
                        },
                        shape = CircleShape,
                        color = Color(0xCC1E1A10),
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                        modifier = Modifier
                            .border(1.dp, PixelAmber.copy(alpha = 0.55f), CircleShape)
                            .semantics(mergeDescendants = true) {}
                            .testTag("night_sight_indicator_pill"),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Bedtime,
                                contentDescription = null,
                                tint = PixelAmber,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = PixelAmber,
                            )
                        }
                    }
                }

                // Pure-GPU floating menus above the anchored lens row (ZERO sibling or shutter deck relayout!)
                androidx.compose.animation.AnimatedVisibility(
                    visible = uiState.isZoomRulerVisible && !anyFloatingDrawerOpen,
                    enter = fadeIn(tween(130)) +
                        slideInVertically(tween(170, easing = FastOutSlowInEasing)) { it / 3 },
                    exit = fadeOut(tween(110)) +
                        slideOutVertically(tween(140, easing = FastOutSlowInEasing)) { it / 3 },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 64.dp),
                ) {
                    PixelZoomSliderOverlay(
                        currentZoom = uiState.currentZoomRatio,
                        minZoom = uiState.telemetry.minZoomRatio,
                        maxZoom = uiState.telemetry.maxZoomRatio,
                        onZoomChange = viewModel::onZoomSliderChange,
                    )
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = uiState.isLooksBarExpanded,
                    enter = fadeIn(tween(130)) +
                        slideInVertically(tween(190, easing = FastOutSlowInEasing)) { it / 3 },
                    exit = fadeOut(tween(110)) +
                        slideOutVertically(tween(150, easing = FastOutSlowInEasing)) { it / 3 },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 64.dp),
                ) {
                    PixelLooksBar(
                        presets = uiState.tonePresets,
                        activePresetId = uiState.activePresetId,
                        hasUnsavedToneChanges = uiState.manualSettings.isAnyToneOverride,
                        onSelectPreset = viewModel::applyTonePreset,
                        onDeletePreset = viewModel::deleteTonePreset,
                        onRequestSaveCurrent = { showSaveLookDialogFromBar = true },
                        onOpenToneSliders = viewModel::toggleTonePanel,
                    )
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = uiState.isTonePanelExpanded,
                    enter = fadeIn(tween(130)) +
                        slideInVertically(tween(190, easing = FastOutSlowInEasing)) { it / 3 },
                    exit = fadeOut(tween(110)) +
                        slideOutVertically(tween(150, easing = FastOutSlowInEasing)) { it / 3 },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 64.dp),
                ) {
                    ToneAdjustmentsPanel(
                        manual = uiState.manualSettings,
                        activeTab = uiState.activeToneTab,
                        presets = uiState.tonePresets,
                        activePresetId = uiState.activePresetId,
                        onSelectTab = viewModel::selectToneTab,
                        onShadowsChange = viewModel::updateShadowBoost,
                        onHighlightsChange = viewModel::updateHighlightBoost,
                        onContrastChange = viewModel::updateContrastAdjust,
                        onVibrancyChange = viewModel::updateVibrancyAdjust,
                        onResetTone = viewModel::resetToneAdjustments,
                        onSavePreset = viewModel::saveCurrentToneAsPreset,
                        onApplyPreset = viewModel::applyTonePreset,
                        onDeletePreset = viewModel::deleteTonePreset,
                    )
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = uiState.isProPanelExpanded,
                    enter = fadeIn(tween(130)) +
                        slideInVertically(tween(190, easing = FastOutSlowInEasing)) { it / 3 },
                    exit = fadeOut(tween(110)) +
                        slideOutVertically(tween(150, easing = FastOutSlowInEasing)) { it / 3 },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 64.dp),
                ) {
                    ProControlsPanel(
                        manual = uiState.manualSettings,
                        activeTab = uiState.activeProTab,
                        minFocusDiopter = uiState.telemetry.minFocusDiopter,
                        hardwareManualSupported = uiState.telemetry.manualControlHardwareSupported,
                        onSelectTab = viewModel::selectProTab,
                        onEvChange = viewModel::updateExposureEv,
                        onShadowChange = viewModel::updateShadowBoost,
                        onHighlightChange = viewModel::updateHighlightBoost,
                        onContrastChange = viewModel::updateContrastAdjust,
                        onVibrancyChange = viewModel::updateVibrancyAdjust,
                        onWbChange = viewModel::updateWhiteBalanceKelvin,
                        onIsoChange = viewModel::updateIso,
                        onShutterChange = viewModel::updateShutterSpeedNs,
                        onFocusChange = viewModel::updateFocusDistance,
                        onResetAll = viewModel::resetManualSettings,
                    )
                }

                // Anchored Lens Pill Selector + Settings/Pro Corner Buttons
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp, start = 14.dp, end = 14.dp)
                        .fillMaxWidth()
                        .widthIn(max = 680.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExpressiveIconButton(
                        icon = Icons.Rounded.Settings,
                        contentDescription = stringResource(R.string.cd_settings),
                        active = uiState.isSettingsSheetOpen,
                        shapeStyle = ExpressiveButtonShape.COOKIE_PERSISTENT,
                        activeContainerColor = MaterialTheme.colorScheme.secondary,
                        activeContentColor = MaterialTheme.colorScheme.onSecondary,
                        inactiveContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        inactiveContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        iconRotationDegrees = if (uiState.isSettingsSheetOpen) 60f else 0f,
                        size = 44.dp,
                        iconSize = 20.dp,
                        onClick = { viewModel.setSettingsSheetOpen(true) },
                        testTag = "vf_settings_button",
                    )

                    PixelLensZoomBar(
                        lenses = uiState.telemetry.lensOptions,
                        activeLensId = uiState.activeLensId,
                        currentZoom = uiState.currentZoomRatio,
                        onSelectLens = viewModel::selectLens,
                        onToggleRuler = viewModel::toggleZoomRuler,
                    )

                    val proLocked = uiState.isPixelServicesLocked
                    val proActive = !proLocked && (uiState.isProPanelExpanded || uiState.manualSettings.isAnyManualOverride)
                    ExpressiveIconButton(
                        icon = if (proLocked) Icons.Rounded.Lock else Icons.Rounded.Tune,
                        contentDescription = if (proLocked) {
                            "Pro controls locked by Pixel Camera Services"
                        } else {
                            stringResource(R.string.cd_pro_controls)
                        },
                        active = proActive,
                        shapeStyle = ExpressiveButtonShape.CIRCLE_TO_CLOVER,
                        activeContainerColor = MaterialTheme.colorScheme.tertiary,
                        activeContentColor = MaterialTheme.colorScheme.onTertiary,
                        inactiveContainerColor = if (proLocked) {
                            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f)
                        } else {
                            MaterialTheme.colorScheme.tertiaryContainer
                        },
                        inactiveContentColor = if (proLocked) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                        } else {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        },
                        iconRotationDegrees = if (!proLocked && uiState.isProPanelExpanded) 90f else 0f,
                        size = 44.dp,
                        iconSize = 20.dp,
                        onClick = viewModel::toggleProPanel,
                        testTag = "pro_toggle_button",
                    )
                }
            }

            // Anchored Expressive Shutter Deck (never shifts or relayouts when menus open)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.36f),
                                Color.Black.copy(alpha = 0.70f),
                            )
                        )
                    )
                    .padding(top = 6.dp, bottom = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                PixelShutterDeck(
                    uiState = uiState,
                    onSwitchCamera = viewModel::switchLensFacing,
                    onCapture = viewModel::capturePhoto,
                    onOpenGallery = { viewModel.setInspectorOpen(true) },
                )
            }
        }
    }

    if (showSaveLookDialogFromBar) {
        SaveTonePresetDialog(
            suggestedName = "Look ${uiState.tonePresets.size + 1}",
            onDismiss = { showSaveLookDialogFromBar = false },
            onSave = { name ->
                viewModel.saveCurrentToneAsPreset(name)
                showSaveLookDialogFromBar = false
            },
        )
    }
}

@Composable
private fun PixelTopBar(
    uiState: CameraUiState,
    onToggleQuickSettings: () -> Unit,
    onCycleFlash: () -> Unit,
    onCycleNightSight: () -> Unit,
    onCycleTimer: () -> Unit,
    onCycleHdr: () -> Unit,
    onToggleLooks: () -> Unit,
    onToggleTone: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExpressiveIconButton(
            icon = Icons.Rounded.KeyboardArrowDown,
            contentDescription = "Quick camera controls",
            active = uiState.isQuickSettingsExpanded,
            shapeStyle = ExpressiveButtonShape.CIRCLE_TO_SQUARE,
            activeContainerColor = MaterialTheme.colorScheme.primary,
            activeContentColor = MaterialTheme.colorScheme.onPrimary,
            inactiveContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            inactiveContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            iconRotationDegrees = if (uiState.isQuickSettingsExpanded) 180f else 0f,
            size = 42.dp,
            iconSize = 21.dp,
            onClick = onToggleQuickSettings,
            testTag = "top_quick_menu_button",
        )

        ExpressiveFloatingToolbar(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            val nightIcon = when (uiState.nightSightMode) {
                NightSightMode.OFF -> Icons.Rounded.BedtimeOff
                NightSightMode.ON -> Icons.Rounded.NightsStay
                NightSightMode.AUTO -> Icons.Rounded.Bedtime
            }
            ExpressiveIconButton(
                icon = nightIcon,
                contentDescription = stringResource(R.string.cd_night_sight),
                active = uiState.isNightSightActive || uiState.nightSightMode == NightSightMode.ON,
                shapeStyle = ExpressiveButtonShape.CIRCLE_TO_COOKIE,
                activeContainerColor = PixelAmber,
                activeContentColor = Color(0xFF1A1400),
                inactiveContainerColor = Color.Transparent,
                inactiveContentColor = if (uiState.nightSightMode == NightSightMode.OFF) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                badgeText = if (uiState.nightSightMode == NightSightMode.ON) "ON" else null,
                size = 38.dp,
                iconSize = 19.dp,
                onClick = onCycleNightSight,
                testTag = "top_night_sight_button",
            )

            val flashIcon = when (uiState.flashMode) {
                FlashMode.OFF -> Icons.Rounded.FlashOff
                FlashMode.AUTO -> Icons.Rounded.FlashAuto
                FlashMode.ON -> Icons.Rounded.FlashOn
            }
            ExpressiveIconButton(
                icon = flashIcon,
                contentDescription = stringResource(R.string.cd_flash),
                active = uiState.flashMode == FlashMode.ON,
                shapeStyle = ExpressiveButtonShape.CIRCLE_TO_CLOVER,
                activeContainerColor = MaterialTheme.colorScheme.secondary,
                activeContentColor = MaterialTheme.colorScheme.onSecondary,
                inactiveContainerColor = Color.Transparent,
                inactiveContentColor = MaterialTheme.colorScheme.onSurface,
                size = 38.dp,
                iconSize = 19.dp,
                onClick = onCycleFlash,
                testTag = "top_flash_button",
            )

            ExpressiveIconButton(
                icon = Icons.Rounded.Timer,
                contentDescription = "Timer ${uiState.timerDuration.label}",
                active = uiState.timerDuration != TimerDuration.OFF,
                shapeStyle = ExpressiveButtonShape.CIRCLE_TO_COOKIE,
                activeContainerColor = MaterialTheme.colorScheme.primary,
                activeContentColor = MaterialTheme.colorScheme.onPrimary,
                inactiveContainerColor = Color.Transparent,
                inactiveContentColor = MaterialTheme.colorScheme.onSurface,
                badgeText = if (uiState.timerDuration != TimerDuration.OFF) uiState.timerDuration.label else null,
                size = 38.dp,
                iconSize = 19.dp,
                onClick = onCycleTimer,
                testTag = "top_timer_button",
            )

            val hdrDisabledByLowLight = uiState.isNightSightActive
            val displayHdrMode = uiState.effectiveHdrMode
            val hdrIcon = when (displayHdrMode) {
                HdrMode.OFF -> Icons.Rounded.HdrOff
                HdrMode.HDR_PLUS -> Icons.Rounded.HdrOn
                HdrMode.HDR_PLUS_ENHANCED -> Icons.Rounded.HdrEnhancedSelect
            }
            ExpressiveIconButton(
                icon = hdrIcon,
                contentDescription = if (hdrDisabledByLowLight) {
                    "HDR Off (disabled in Low Light)"
                } else {
                    displayHdrMode.label
                },
                active = !hdrDisabledByLowLight && displayHdrMode == HdrMode.HDR_PLUS_ENHANCED,
                shapeStyle = ExpressiveButtonShape.CIRCLE_TO_COOKIE,
                activeContainerColor = MaterialTheme.colorScheme.tertiary,
                activeContentColor = MaterialTheme.colorScheme.onTertiary,
                inactiveContainerColor = Color.Transparent,
                inactiveContentColor = if (hdrDisabledByLowLight) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                size = 38.dp,
                iconSize = 19.dp,
                onClick = onCycleHdr,
                testTag = "top_hdr_button",
            )

            ExpressiveIconButton(
                icon = Icons.Rounded.Tonality,
                contentDescription = "Tone & Presets",
                active = uiState.isTonePanelExpanded || uiState.manualSettings.isAnyToneOverride,
                shapeStyle = ExpressiveButtonShape.CIRCLE_TO_CLOVER,
                activeContainerColor = MaterialTheme.colorScheme.primary,
                activeContentColor = MaterialTheme.colorScheme.onPrimary,
                inactiveContainerColor = Color.Transparent,
                inactiveContentColor = MaterialTheme.colorScheme.onSurface,
                size = 38.dp,
                iconSize = 19.dp,
                onClick = onToggleTone,
                testTag = "top_tone_button",
            )

            ExpressiveIconButton(
                icon = Icons.Rounded.AutoAwesome,
                contentDescription = "Saved Looks",
                active = uiState.isLooksBarExpanded || uiState.activePresetId != null,
                shapeStyle = ExpressiveButtonShape.CIRCLE_TO_BURST,
                activeContainerColor = MaterialTheme.colorScheme.secondary,
                activeContentColor = MaterialTheme.colorScheme.onSecondary,
                inactiveContainerColor = Color.Transparent,
                inactiveContentColor = MaterialTheme.colorScheme.onSurface,
                size = 38.dp,
                iconSize = 19.dp,
                onClick = onToggleLooks,
                testTag = "top_looks_button",
            )
        }
    }
}

@Composable
private fun PixelQuickSettingsDrawer(
    uiState: CameraUiState,
    onSelectFlash: (FlashMode) -> Unit,
    onSelectNightSight: (NightSightMode) -> Unit,
    onSelectTimer: (TimerDuration) -> Unit,
    onSelectAspect: (AspectRatioMode) -> Unit,
    onSelectHdr: (HdrMode) -> Unit,
    onOpenMoreSettings: () -> Unit,
) {
    ExpressiveSurfacePanel(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 420.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickDrawerRow(
                label = "Night",
                options = NightSightMode.entries.map { it to it.shortLabel },
                selected = uiState.nightSightMode,
                onSelect = onSelectNightSight,
            )
            QuickDrawerRow(
                label = "Flash",
                options = FlashMode.entries.map { it to it.label },
                selected = uiState.flashMode,
                onSelect = onSelectFlash,
            )
            QuickDrawerRow(
                label = "HDR+",
                options = HdrMode.entries.map { it to it.badge },
                selected = uiState.effectiveHdrMode,
                enabled = !uiState.isNightSightActive,
                onSelect = onSelectHdr,
            )
            QuickDrawerRow(
                label = "Timer",
                options = TimerDuration.entries.map { it to it.label },
                selected = uiState.timerDuration,
                onSelect = onSelectTimer,
            )
            QuickDrawerRow(
                label = "Ratio",
                options = AspectRatioMode.entries.map { it to it.label },
                selected = uiState.aspectRatio,
                onSelect = onSelectAspect,
            )

            ExpressiveFilledButton(
                text = "More settings",
                leadingIcon = Icons.Rounded.Settings,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = onOpenMoreSettings,
                modifier = Modifier.fillMaxWidth(),
                testTag = "quick_more_settings_button",
            )
        }
    }
}

@Composable
private fun <T> QuickDrawerRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            },
            modifier = Modifier.width(50.dp),
        )
        ExpressiveSegmentedGroup(
            options = options,
            selected = selected,
            onSelect = onSelect,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PixelLensZoomBar(
    lenses: List<LensOption>,
    activeLensId: String,
    currentZoom: Float,
    onSelectLens: (LensOption) -> Unit,
    onToggleRuler: () -> Unit,
) {
    val haptics = rememberCameraHaptics()
    val activeDiscrete = lenses.firstOrNull { it.id == activeLensId && it.cameraId != null }
    val closestLensId = if (activeDiscrete != null && abs(currentZoom - activeDiscrete.zoomRatio) < 0.15f) {
        activeDiscrete.id
    } else {
        lenses.minByOrNull { lens -> abs(lens.zoomRatio - currentZoom) }?.id
    }

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            lenses.forEach { lens ->
                val selected = lens.id == closestLensId
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()

                val scaleState = animateFloatAsState(
                    targetValue = if (isPressed) 0.88f else 1f,
                    animationSpec = M3ExpressiveMotion.fastSpatial(),
                    label = "lens_pill_scale",
                )
                val horizPad by animateDpAsState(
                    targetValue = if (selected) 13.dp else 10.dp,
                    animationSpec = M3ExpressiveMotion.fastSpatial(),
                    label = "lens_pill_pad",
                )
                val pillCorner by animateDpAsState(
                    targetValue = when {
                        isPressed -> 8.dp
                        selected -> 20.dp
                        else -> 11.dp
                    },
                    animationSpec = M3ExpressiveMotion.fastSpatial(),
                    label = "lens_pill_corner",
                )
                val pillBg by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                    animationSpec = M3ExpressiveMotion.fastEffects(),
                    label = "lens_pill_bg",
                )
                val pillFg by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    animationSpec = M3ExpressiveMotion.fastEffects(),
                    label = "lens_pill_fg",
                )

                val displayText = if (selected) {
                    if (currentZoom < 0.95f) String.format("%.1fx", currentZoom).removePrefix("0")
                    else if (abs(currentZoom - currentZoom.toInt()) < 0.08f) "${currentZoom.toInt()}x"
                    else String.format("%.1fx", currentZoom)
                } else {
                    lens.label
                }

                Surface(
                    onClick = {
                        haptics.tick()
                        if (selected) {
                            onToggleRuler()
                        } else {
                            onSelectLens(lens)
                        }
                    },
                    interactionSource = interactionSource,
                    shape = RoundedCornerShape(pillCorner),
                    color = pillBg,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .height(34.dp)
                        .graphicsLayer {
                            val s = scaleState.value
                            scaleX = s
                            scaleY = s
                        }
                        .testTag("lens_pill_${lens.id}"),
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = horizPad),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.labelMedium,
                            color = pillFg,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PixelZoomSliderOverlay(
    currentZoom: Float,
    minZoom: Float,
    maxZoom: Float,
    onZoomChange: (Float) -> Unit,
) {
    val safeMax = maxZoom.coerceAtLeast(minZoom + 0.5f)
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.widthIn(max = 296.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondary,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Text(
                    text = String.format("%.1fx", currentZoom),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
            ExpressiveSlider(
                value = currentZoom.coerceIn(minZoom, safeMax),
                onValueChange = onZoomChange,
                valueRange = minZoom..safeMax,
                accentColor = MaterialTheme.colorScheme.secondary,
                onAccentColor = MaterialTheme.colorScheme.onSecondary,
                showCenterDetent = false,
                hapticStep = 0.25f,
                compact = true,
                sliderTestTag = "viewfinder_zoom_slider",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PixelShutterDeck(
    uiState: CameraUiState,
    onSwitchCamera: () -> Unit,
    onCapture: () -> Unit,
    onOpenGallery: () -> Unit,
) {
    val haptics = rememberCameraHaptics()
    val isCapturing = uiState.captureProgress.isCapturing
    val isNightSightReady = uiState.isNightSightActive
    val isNightCapturing = isCapturing && uiState.captureProgress.isNightSightCapture

    var switchSpinDegrees by remember { mutableFloatStateOf(0f) }

    // Reactive shutter interaction source for expressive press-and-hold scalloped cookie morphing
    val shutterInteraction = remember { MutableInteractionSource() }
    val shutterPressed by shutterInteraction.collectIsPressedAsState()
    val shutterInnerScaleState = animateFloatAsState(
        targetValue = when {
            isCapturing -> 0.84f
            shutterPressed -> 0.86f
            else -> 1f
        },
        animationSpec = M3ExpressiveMotion.fastSpatial(),
        label = "shutter_inner_scale",
    )
    val shutterOuterScaleState = animateFloatAsState(
        targetValue = when {
            isCapturing -> 1.06f
            shutterPressed -> 0.96f
            else -> 1f
        },
        animationSpec = M3ExpressiveMotion.fastSpatial(),
        label = "shutter_outer_scale",
    )
    val shutterMorphAmp by animateFloatAsState(
        targetValue = when {
            isCapturing -> 0.115f
            shutterPressed -> 0.095f
            else -> 0f
        },
        animationSpec = M3ExpressiveMotion.fastSpatial(),
        label = "shutter_cookie_amp",
    )
    val nightHoldProgress by animateFloatAsState(
        targetValue = if (isNightCapturing) uiState.captureProgress.progress.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 85, easing = LinearEasing),
        label = "night_hold_progress",
    )
    // Spin ONLY while actively capturing — zero Choreographer VSYNC ticks when idle
    val captureSpinDeg = remember { Animatable(0f) }
    LaunchedEffect(isCapturing) {
        if (isCapturing) {
            captureSpinDeg.snapTo(0f)
            captureSpinDeg.animateTo(
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 2000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            )
        } else {
            captureSpinDeg.snapTo(0f)
        }
    }
    val shutterInnerColorState = animateColorAsState(
        targetValue = when {
            isNightCapturing -> PixelAmber
            isCapturing -> MaterialTheme.colorScheme.tertiary
            shutterPressed -> MaterialTheme.colorScheme.primary
            isNightSightReady -> Color(0xFFFFF4D6)
            else -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = M3ExpressiveMotion.fastEffects(),
        label = "shutter_inner_color",
    )
    val shutterRingColor by animateColorAsState(
        targetValue = when {
            isNightCapturing -> PixelAmber.copy(alpha = 0.28f)
            isCapturing -> MaterialTheme.colorScheme.tertiary
            isNightSightReady -> PixelAmber
            else -> Color.White
        },
        animationSpec = M3ExpressiveMotion.fastEffects(),
        label = "shutter_ring_color",
    )

    // Celebratory pop on gallery thumbnail whenever a new photo arrives
    val latestCaptureTs = uiState.capturedPhotos.firstOrNull()?.timestampMs ?: 0L
    val galleryPop = remember { Animatable(1f) }
    LaunchedEffect(latestCaptureTs) {
        if (latestCaptureTs > 0L) {
            galleryPop.snapTo(1.22f)
            galleryPop.animateTo(1f, M3ExpressiveMotion.fastSpatial())
        }
    }
    val galleryInteraction = remember { MutableInteractionSource() }
    val galleryPressed by galleryInteraction.collectIsPressedAsState()
    val galleryPressScaleState = animateFloatAsState(
        targetValue = if (galleryPressed) 0.88f else 1f,
        animationSpec = M3ExpressiveMotion.fastSpatial(),
        label = "gallery_press_scale",
    )
    val galleryCornerPercent by animateFloatAsState(
        targetValue = if (galleryPressed) 26f else 50f,
        animationSpec = M3ExpressiveMotion.fastSpatial(),
        label = "gallery_corner",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 460.dp)
            .padding(horizontal = 30.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: Material 3 Expressive 4-Leaf Clover Switch Camera button with 180° spring flip
        ExpressiveIconButton(
            icon = Icons.Rounded.Cameraswitch,
            contentDescription = stringResource(R.string.cd_switch_camera),
            active = uiState.lensFacing == CameraSelector.LENS_FACING_FRONT,
            shapeStyle = ExpressiveButtonShape.CIRCLE_TO_CLOVER,
            activeContainerColor = MaterialTheme.colorScheme.primary,
            activeContentColor = MaterialTheme.colorScheme.onPrimary,
            inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            inactiveContentColor = MaterialTheme.colorScheme.onSurface,
            size = 56.dp,
            iconSize = 24.dp,
            iconRotationDegrees = switchSpinDegrees,
            hapticStyle = ExpressiveHaptic.ACTION,
            onClick = {
                switchSpinDegrees += 180f
                onSwitchCamera()
            },
            testTag = "switch_camera_button",
        )

        // Center: Material 3 Expressive Scalloped-Cookie Morphing Shutter Button + Night Sight Hold Ring
        Surface(
            onClick = {
                if (!isCapturing) {
                    haptics.shutterClick()
                    onCapture()
                }
            },
            enabled = !isCapturing,
            interactionSource = shutterInteraction,
            shape = CircleShape,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier
                .size(86.dp)
                .testTag("shutter_button"),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .graphicsLayer {
                            val s = shutterOuterScaleState.value
                            scaleX = s
                            scaleY = s
                        }
                        .border(
                            width = 3.dp,
                            color = shutterRingColor,
                            shape = CircleShape,
                        ),
                )
                // Smooth 360° circular progress sweep while holding shutter open in Night Sight
                if (isNightCapturing) {
                    Canvas(
                        modifier = Modifier
                            .size(80.dp)
                            .graphicsLayer {
                                val s = shutterOuterScaleState.value
                                scaleX = s
                                scaleY = s
                            }
                    ) {
                        val strokePx = 4.dp.toPx()
                        val inset = strokePx / 2f
                        drawArc(
                            color = PixelAmber,
                            startAngle = -90f,
                            sweepAngle = (nightHoldProgress * 360f).coerceIn(8f, 360f),
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = ComposeSize(size.width - strokePx, size.height - strokePx),
                            style = Stroke(width = strokePx, cap = StrokeCap.Round),
                        )
                    }
                }
                val quantShutterAmp = (shutterMorphAmp * 100f).toInt() / 100f
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .graphicsLayer {
                            val s = shutterInnerScaleState.value
                            scaleX = s
                            scaleY = s
                            rotationZ = captureSpinDeg.value
                        }
                        .drawExpressiveShapeBackground(
                            lobes = 10,
                            amplitude = quantShutterAmp,
                            colorProvider = { shutterInnerColorState.value },
                        ),
                )
                // Pixel-style Crescent Moon emblem inside the shutter button when Night Sight is active
                androidx.compose.animation.AnimatedVisibility(
                    visible = isNightSightReady || isNightCapturing,
                    enter = fadeIn(M3ExpressiveMotion.fastEffects()) +
                        scaleIn(initialScale = 0.65f, animationSpec = M3ExpressiveMotion.fastSpatial()),
                    exit = fadeOut(M3ExpressiveMotion.fastEffects()) +
                        scaleOut(targetScale = 0.65f, animationSpec = M3ExpressiveMotion.fastEffects()),
                ) {
                    Icon(
                        imageVector = if (isNightCapturing) Icons.Rounded.NightsStay else Icons.Rounded.Bedtime,
                        contentDescription = null,
                        tint = Color(0xFF1A1400),
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }

        // Right: Flat Matte Material 3 Expressive Gallery Thumbnail with morphing cookie loader
        val galleryShape = RoundedCornerShape(percent = galleryCornerPercent.toInt().coerceIn(24, 50))
        Surface(
            onClick = {
                haptics.actionClick()
                onOpenGallery()
            },
            interactionSource = galleryInteraction,
            shape = galleryShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier
                .size(56.dp)
                .graphicsLayer {
                    val s = galleryPop.value * galleryPressScaleState.value
                    scaleX = s
                    scaleY = s
                }
                .testTag("gallery_thumbnail_button"),
        ) {
            Box(contentAlignment = Alignment.Center) {
                val thumbUri = uiState.capturedPhotos.firstOrNull()?.uriString ?: uiState.latestGalleryThumbnailUri
                if (thumbUri != null) {
                    AsyncImage(
                        model = Uri.parse(thumbUri),
                        contentDescription = stringResource(R.string.cd_open_gallery),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.PhotoLibrary,
                        contentDescription = stringResource(R.string.cd_open_gallery),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
                if (uiState.captureProgress.savingCount > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        ExpressiveLoadingIndicator(
                            size = 24.dp,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPermissionOnboarding(
    showSettingsButton: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        ExpressiveSurfacePanel(
            modifier = Modifier.widthIn(max = 420.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            cornerRadius = 32.dp,
            contentPadding = PaddingValues(28.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    shape = ExpressiveShapes.Cookie9,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    modifier = Modifier.size(76.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.CameraAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.camera_permission_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = stringResource(R.string.camera_permission_rationale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                ExpressiveFilledButton(
                    text = stringResource(R.string.grant_camera_permission),
                    onClick = onRequestPermission,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    testTag = "grant_camera_permission_button",
                )

                if (showSettingsButton) {
                    ExpressiveOutlinedButton(
                        text = stringResource(R.string.open_app_settings),
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    )
                }
            }
        }
    }
}
