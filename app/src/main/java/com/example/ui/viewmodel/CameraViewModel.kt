package com.example.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.example.camera.hardware.CameraController
import com.example.camera.hardware.HorizonOrientation
import com.example.camera.hardware.HorizonSensorMonitor
import com.example.camera.hardware.RawCapturedFrame
import com.example.camera.model.AspectRatioMode
import com.example.camera.model.BacklightMode
import com.example.camera.model.CameraMode
import com.example.camera.model.CapturedPhotoRecord
import com.example.camera.model.DetectedFace
import com.example.camera.model.FlashMode
import com.example.camera.model.GridOverlayType
import com.example.camera.model.HdrMode
import com.example.camera.model.HdrPipelineConfig
import com.example.camera.model.LiveTelemetry
import com.example.camera.model.LowLightDetector
import com.example.camera.model.ManualExposureSettings
import com.example.camera.model.NightSightMode
import com.example.camera.model.PixelExtensionMode
import com.example.camera.model.ProDialTab
import com.example.camera.model.TimerDuration
import com.example.camera.model.TonePreset
import com.example.camera.processing.HdrPlusEngine
import com.example.camera.processing.LiveFrameAnalysisResult
import com.example.camera.processing.MediaStoreRepository
import com.example.data.PersistedCameraPreferences
import com.example.data.SettingsDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.math.roundToInt

enum class TonePanelTab {
    ADJUST,
    PRESETS,
}

@Immutable
data class FocusReticleState(
    val xPx: Float,
    val yPx: Float,
    val normX: Float,
    val normY: Float,
    val visible: Boolean = true,
    val isFocused: Boolean? = null,
    val isAeAfLocked: Boolean = false,
)

@Immutable
data class CaptureProgressState(
    val isCapturing: Boolean = false,
    val isNightSightCapture: Boolean = false,
    val holdSecondsRemaining: Float = 0f,
    val savingCount: Int = 0,
    val progress: Float = 0f,
    val statusText: String = "",
)

@Immutable
data class CameraUiState(
    val cameraMode: CameraMode = CameraMode.CAMERA,
    val hdrMode: HdrMode = HdrMode.HDR_PLUS_ENHANCED,
    val nightSightMode: NightSightMode = NightSightMode.AUTO,
    val pixelCameraServicesEnabled: Boolean = false,
    val pixelExtensionsSupported: Boolean = false,
    val supportedPixelExtensions: Set<PixelExtensionMode> = emptySet(),
    val hasCheckedPixelExtensions: Boolean = false,
    val activePixelExtensionMode: PixelExtensionMode = PixelExtensionMode.NONE,
    val nativeUltraHdrSupported: Boolean = false,
    val nativeUltraHdrActive: Boolean = false,
    val isAnalysisActiveInSession: Boolean = true,
    val isLowLightDetected: Boolean = false,
    val isBacklitDetected: Boolean = false,
    val detectedFaceCount: Int = 0,
    val lowLightSeverity: Float = 0f,
    val estimatedNightHoldSeconds: Float = 0f,
    val isLowLightBoostActive: Boolean = false,
    val tonePresets: List<TonePreset> = emptyList(),
    val activePresetId: String? = null,
    val activeToneTab: TonePanelTab = TonePanelTab.ADJUST,
    val flashMode: FlashMode = FlashMode.OFF,
    val timerDuration: TimerDuration = TimerDuration.OFF,
    val aspectRatio: AspectRatioMode = AspectRatioMode.RATIO_4_3,
    val gridOverlay: GridOverlayType = GridOverlayType.RULE_OF_THIRDS,
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    val preferredCameraId: String? = null,
    val activeLensId: String = "main_1x",
    val currentZoomRatio: Float = 1f,
    val isZoomRulerVisible: Boolean = false,
    val manualSettings: ManualExposureSettings = ManualExposureSettings(),
    val activeProTab: ProDialTab = ProDialTab.EV,
    val isProPanelExpanded: Boolean = false,
    val isTonePanelExpanded: Boolean = false,
    val isLooksBarExpanded: Boolean = false,
    val isQuickSettingsExpanded: Boolean = false,
    val isSettingsSheetOpen: Boolean = false,
    val isInspectorOpen: Boolean = false,
    val showHistogram: Boolean = true,
    val showHorizonLevel: Boolean = true,
    val showFocusPeaking: Boolean = false,
    val showZebraHighlights: Boolean = false,
    val hdrConfig: HdrPipelineConfig = HdrPipelineConfig(),
    val telemetry: LiveTelemetry = LiveTelemetry(),
    val focusReticle: FocusReticleState? = null,
    val captureProgress: CaptureProgressState = CaptureProgressState(),
    val countdownSecondsLeft: Int = 0,
    val shutterFlashTick: Long = 0L,
    val capturedPhotos: List<CapturedPhotoRecord> = emptyList(),
    val selectedPhotoIndex: Int = 0,
    val latestGalleryThumbnailUri: String? = null,
    val statusBannerMessage: String? = null,
) {
    val activePreset: TonePreset?
        get() = tonePresets.firstOrNull { it.id == activePresetId }

    /**
     * True when Pixel Camera Services Night Sight extension is supported on this lens.
     * Pixel Camera Services is used exclusively for Night Sight; all daylight, HDR+,
     * Backlight, and Ultra HDR modes always run on the custom computational pipeline.
     */
    val isPixelNightOnlyDevice: Boolean
        get() = hasCheckedPixelExtensions && PixelExtensionMode.NIGHT in supportedPixelExtensions

    /** True when Auto Night Sight wants Night mode right now (low light detected, flash not forced, auto exposure). */
    val isAutoNightSightTriggered: Boolean
        get() = nightSightMode == NightSightMode.AUTO &&
            isLowLightDetected &&
            flashMode != FlashMode.ON &&
            !manualSettings.isManualExposureActive

    /**
     * Target CameraX Extensions mode: Pixel Camera Services is strictly limited to Night Sight.
     * Daylight, HDR+, Backlight, and Ultra HDR ALWAYS run on the custom pipeline even while
     * Pixel Camera Services is enabled in settings.
     */
    val desiredPixelExtensionMode: PixelExtensionMode
        get() = when {
            !pixelCameraServicesEnabled -> PixelExtensionMode.NONE
            nightSightMode == NightSightMode.ON || isAutoNightSightTriggered -> {
                if (!hasCheckedPixelExtensions || PixelExtensionMode.NIGHT in supportedPixelExtensions) {
                    PixelExtensionMode.NIGHT
                } else {
                    PixelExtensionMode.NONE
                }
            }
            else -> PixelExtensionMode.NONE
        }

    /** True when a Pixel Camera Services (CameraX Extensions) session is actively bound. */
    val isPixelServicesActive: Boolean
        get() = pixelCameraServicesEnabled && activePixelExtensionMode != PixelExtensionMode.NONE

    /** Locks incompatible manual hardware, custom HDR+ tuning, and ImageAnalysis controls while an Extension session is active. */
    val isPixelServicesLocked: Boolean
        get() = isPixelServicesActive

    val isNightSightActive: Boolean
        get() = if (isPixelServicesActive) {
            activePixelExtensionMode == PixelExtensionMode.NIGHT || nightSightMode == NightSightMode.ON
        } else {
            !manualSettings.isManualExposureActive && when (nightSightMode) {
                NightSightMode.ON -> true
                NightSightMode.AUTO -> isLowLightDetected && flashMode != FlashMode.ON
                NightSightMode.OFF -> false
            }
        }

    /** True when Backlight Picture optimization should be actively applied to captures and preview. */
    val isBacklightActive: Boolean
        get() = !isNightSightActive &&
            !isPixelServicesLocked &&
            when (hdrConfig.backlightMode) {
                BacklightMode.ON -> true
                BacklightMode.AUTO -> isBacklitDetected
                BacklightMode.OFF -> false
            }

    /** Automatically disables all HDR modes while Low Light / Night Sight is active. */
    val effectiveHdrMode: HdrMode
        get() = if (isNightSightActive) HdrMode.OFF else hdrMode

    /** Automatically disables Ultra HDR gainmap while Low Light or Pixel Camera Services is active. */
    val effectiveUltraHdrEnabled: Boolean
        get() = !isNightSightActive &&
            !isPixelServicesLocked &&
            hdrConfig.ultraHdrEnabled &&
            effectiveHdrMode != HdrMode.OFF

    /**
     * Whether standard daylight ImageCapture should bind with CameraX OUTPUT_FORMAT_JPEG_ULTRA_HDR.
     * Deliberately excludes transient isLowLightDetected changes so custom Auto Night Sight never
     * causes viewfinder rebind flicker; HdrPlusEngine discards any gainmap on low-light shots.
     */
    val wantsNativeUltraHdrCapture: Boolean
        get() = desiredPixelExtensionMode == PixelExtensionMode.NONE &&
            nightSightMode != NightSightMode.ON &&
            hdrMode != HdrMode.OFF &&
            hdrConfig.ultraHdrEnabled
}

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val cameraController = CameraController(application)
    private val hdrPlusEngine = HdrPlusEngine()
    private val mediaStoreRepository = MediaStoreRepository(application)
    private val settingsDataStore = SettingsDataStore(application)
    private val horizonMonitor = HorizonSensorMonitor(application)

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    // Isolated high-frequency streams so sensor/histogram updates NEVER trigger full-screen UI recompositions
    private val _horizonState = MutableStateFlow(HorizonOrientation())
    val horizonState: StateFlow<HorizonOrientation> = _horizonState.asStateFlow()

    private val _histogramState = MutableStateFlow(LiveFrameAnalysisResult())
    val histogramState: StateFlow<LiveFrameAnalysisResult> = _histogramState.asStateFlow()

    private val _detectedFacesState = MutableStateFlow<List<DetectedFace>>(emptyList())
    val detectedFacesState: StateFlow<List<DetectedFace>> = _detectedFacesState.asStateFlow()

    private var cachedPrefs: PersistedCameraPreferences = PersistedCameraPreferences()
    private var prefsLoaded = false
    private var persistPrefsJob: Job? = null
    private var smoothedLowLightSeverity: Float = -1f
    private var hasShownNightOnlyNotice: Boolean = false
    @Volatile
    private var latestAmbientLux: Float? = null
    private var autoPixelNightEntryLux: Float? = null
    private var lastAutoPixelNightExitMs: Long = 0L
    private var lastAutoPixelNightExitLuma: Float = 0f
    private var daylightSteadySinceMs: Long = 0L
    private var adaptiveAutoNightExitLuma: Float = DEFAULT_AUTO_NIGHT_EXIT_LUMA

    private class PendingRenderJob(
        val rawFrames: List<RawCapturedFrame>,
        val cameraMode: CameraMode,
        val hdrMode: HdrMode,
        val manualSettings: ManualExposureSettings,
        val hdrConfig: HdrPipelineConfig,
        val activeLookName: String,
        val focusNorm: Pair<Float, Float>?,
        val isoUsed: Int,
        val shutterUsed: String,
        val wbUsed: Int,
        val isNightSight: Boolean = false,
        val lowLightSeverity: Float = 0f,
        val totalExposureSeconds: Float = 0f,
        val isPixelExtensionCapture: Boolean = false,
        val pixelExtensionMode: PixelExtensionMode = PixelExtensionMode.NONE,
        val isBacklightActive: Boolean = false,
        val detectedFaces: List<DetectedFace> = emptyList(),
    )

    private val renderQueue = Channel<PendingRenderJob>(capacity = MAX_PENDING_RENDERS)

    private var focusHideJob: Job? = null
    private var bannerHideJob: Job? = null
    private var zoomRulerHideJob: Job? = null
    private var captureJob: Job? = null
    private var activePreviewViewRef: WeakReference<PreviewView>? = null

    init {
        viewModelScope.launch {
            val prefs = settingsDataStore.preferencesFlow.first()
            cachedPrefs = prefs
            prefsLoaded = true
            _uiState.update { state ->
                val matchedPreset = prefs.activePresetId?.let { id ->
                    prefs.tonePresets.firstOrNull { it.id == id }
                }
                val nextManual = if (matchedPreset != null && !state.manualSettings.isAnyToneOverride) {
                    state.manualSettings.copy(
                        shadowBoost = matchedPreset.shadowBoost,
                        highlightBoost = matchedPreset.highlightBoost,
                        contrastAdjust = matchedPreset.contrastAdjust,
                        vibrancyAdjust = matchedPreset.vibrancyAdjust,
                    )
                } else {
                    state.manualSettings
                }
                val startInPixelNight = prefs.pixelCameraServicesEnabled && prefs.nightSightMode == NightSightMode.ON
                state.copy(
                    hdrMode = prefs.hdrMode,
                    nightSightMode = prefs.nightSightMode,
                    pixelCameraServicesEnabled = prefs.pixelCameraServicesEnabled,
                    isAnalysisActiveInSession = !startInPixelNight,
                    preferredCameraId = if (startInPixelNight) null else state.preferredCameraId,
                    tonePresets = prefs.tonePresets,
                    activePresetId = prefs.activePresetId,
                    manualSettings = if (startInPixelNight) {
                        nextManual.copy(
                            iso = null,
                            shutterSpeedNs = null,
                            focusDistanceDiopter = null,
                            whiteBalanceKelvin = null,
                        )
                    } else {
                        nextManual
                    },
                    isProPanelExpanded = if (startInPixelNight) false else state.isProPanelExpanded,
                    gridOverlay = prefs.gridOverlay,
                    aspectRatio = prefs.aspectRatio,
                    showHistogram = prefs.showHistogram,
                    showHorizonLevel = prefs.showHorizonLevel,
                    showFocusPeaking = prefs.showFocusPeaking,
                    showZebraHighlights = prefs.showZebraHighlights,
                    hdrConfig = prefs.hdrConfig,
                )
            }
            syncLowLightBoost()
        }

        // Only register the hardware gravity/accelerometer sensor when the level overlay is actually visible
        viewModelScope.launch {
            uiState
                .map { it.showHorizonLevel && !it.isSettingsSheetOpen && !it.isInspectorOpen }
                .distinctUntilChanged()
                .flatMapLatest { active ->
                    if (active) horizonMonitor.observeOrientation() else emptyFlow()
                }
                .collect { horizon ->
                    _horizonState.value = horizon
                }
        }

        // While Auto Pixel Night Sight is active (ImageAnalysis unavailable in Extension sessions),
        // poll the actual live camera viewfinder brightness + hardware AE telemetry + ambient lux
        // every 400ms so returning to a lit room or daylight cleanly exits Pixel Night automatically.
        viewModelScope.launch {
            uiState
                .map {
                    it.activePixelExtensionMode == PixelExtensionMode.NIGHT &&
                        it.nightSightMode == NightSightMode.AUTO &&
                        it.isLowLightDetected &&
                        !it.isSettingsSheetOpen &&
                        !it.isInspectorOpen &&
                        !it.captureProgress.isCapturing
                }
                .distinctUntilChanged()
                .flatMapLatest { monitorForDaylightExit ->
                    if (monitorForDaylightExit) {
                        kotlinx.coroutines.flow.flow {
                            // Ambient lux collector runs alongside the 400ms rear-camera brightness poll
                            kotlinx.coroutines.coroutineScope {
                                autoPixelNightEntryLux = null
                                latestAmbientLux = null
                                val luxJob = launch {
                                    horizonMonitor.observeAmbientLux().collect { lux ->
                                        if (autoPixelNightEntryLux == null) {
                                            autoPixelNightEntryLux = lux
                                        } else if (lux < (autoPixelNightEntryLux ?: lux)) {
                                            autoPixelNightEntryLux = lux
                                        }
                                        latestAmbientLux = lux
                                    }
                                }
                                try {
                                    // Brief settle grace period after binding Pixel Night session
                                    delay(950L)
                                    var consecutiveBrightPolls = 0
                                    while (true) {
                                        val isNowBright = evaluateAutoPixelNightDaylightExit()
                                        if (isNowBright) {
                                            consecutiveBrightPolls++
                                            if (consecutiveBrightPolls >= 3) {
                                                emit(Unit)
                                                break
                                            }
                                        } else {
                                            consecutiveBrightPolls = 0
                                        }
                                        delay(400L)
                                    }
                                } finally {
                                    luxJob.cancel()
                                }
                            }
                        }
                    } else {
                        emptyFlow()
                    }
                }
                .collect {
                    smoothedLowLightSeverity = 0f
                    lastAutoPixelNightExitMs = System.currentTimeMillis()
                    daylightSteadySinceMs = lastAutoPixelNightExitMs
                    _uiState.update { it.copy(isLowLightDetected = false, lowLightSeverity = 0f) }
                }
        }

        viewModelScope.launch {
            val latest = mediaStoreRepository.queryLatestMediaUri()
            if (latest != null) {
                _uiState.update { it.copy(latestGalleryThumbnailUri = latest) }
            }
        }

        // Single serial HDR+ render worker: allows rapid-fire shutter taps while guaranteeing
        // only ONE burst renders at a time so heap usage never multiplies or OOM-crashes.
        viewModelScope.launch(Dispatchers.Default) {
            for (job in renderQueue) {
                processQueuedRenderJob(job)
            }
        }
    }

    private suspend fun processQueuedRenderJob(job: PendingRenderJob) {
        var processedBitmap: Bitmap? = null
        try {
            val processed = hdrPlusEngine.processRawBurst(
                rawFrames = job.rawFrames,
                mode = job.cameraMode,
                hdrMode = job.hdrMode,
                manual = job.manualSettings,
                config = job.hdrConfig,
                focusPointNormalized = job.focusNorm,
                nightSightEnabled = job.isNightSight,
                lowLightSeverity = job.lowLightSeverity,
                totalExposureSeconds = job.totalExposureSeconds,
                isPixelExtensionCapture = job.isPixelExtensionCapture,
                isBacklightActive = job.isBacklightActive,
                detectedFaces = job.detectedFaces,
            )
            processedBitmap = processed.bitmap

            val record = mediaStoreRepository.saveProcessedPhoto(
                bitmap = processed.bitmap,
                mode = job.cameraMode,
                hdrMode = job.hdrMode,
                lookName = job.activeLookName,
                metrics = processed.metrics,
                isoUsed = job.isoUsed,
                shutterUsedLabel = job.shutterUsed,
                evUsed = job.manualSettings.exposureCompensationEv,
                wbKelvinUsed = job.wbUsed,
                hasDngRaw = false,
                lumaHistogram = processed.lumaHistogram,
            )

            if (record != null) {
                _uiState.update { state ->
                    val remSaving = (state.captureProgress.savingCount - 1).coerceAtLeast(0)
                    state.copy(
                        capturedPhotos = listOf(record) + state.capturedPhotos,
                        selectedPhotoIndex = 0,
                        latestGalleryThumbnailUri = record.uriString,
                        captureProgress = if (remSaving == 0 && !state.captureProgress.isCapturing) {
                            CaptureProgressState()
                        } else {
                            state.captureProgress.copy(
                                savingCount = remSaving,
                                statusText = if (remSaving > 0 && !state.captureProgress.isCapturing) {
                                    "Saving $remSaving photo${if (remSaving > 1) "s" else ""}..."
                                } else {
                                    state.captureProgress.statusText
                                }
                            )
                        }
                    )
                }
                val deblurTag = when {
                    processed.metrics.faceDeblurApplied -> " • Face Deblur"
                    processed.metrics.motionDeblurApplied >= 0.25f -> " • Deblur"
                    else -> ""
                }
                if (job.isPixelExtensionCapture) {
                    val extTag = job.pixelExtensionMode.badge.ifBlank { "Pixel Services" }
                    showBanner("$extTag • ${processed.metrics.processingTimeMs}ms")
                } else if (job.isNightSight) {
                    val holdTag = LowLightDetector.formatExposureSeconds(job.totalExposureSeconds.coerceAtLeast(0.5f))
                    showBanner(
                        "Night Sight • ${processed.metrics.framesMerged}f • $holdTag • ${processed.metrics.processingTimeMs}ms"
                    )
                } else if (processed.metrics.isBacklightEnhanced) {
                    val hdrTag = if (record.isUltraHdr) "UHDR" else job.hdrMode.badge
                    showBanner(
                        "$hdrTag • Backlight$deblurTag • ${processed.metrics.framesMerged}f • ${processed.metrics.processingTimeMs}ms"
                    )
                } else {
                    val hdrTag = if (record.isUltraHdr) "UHDR" else job.hdrMode.badge
                    showBanner(
                        "$hdrTag$deblurTag • ${processed.metrics.framesMerged}f • ${processed.metrics.processingTimeMs}ms"
                    )
                }
            } else {
                decrementSavingCount()
                showBanner("Unable to save photo to MediaStore")
            }
        } catch (ce: CancellationException) {
            decrementSavingCount()
            throw ce
        } catch (t: Throwable) {
            Log.e("CameraViewModel", "Background HDR+ render/save failed", t)
            if (t is OutOfMemoryError) {
                System.gc()
            }
            decrementSavingCount()
            showBanner(
                if (t is OutOfMemoryError) "Memory busy — photo skipped" else "Save error: ${t.localizedMessage ?: "Retry"}"
            )
        } finally {
            processedBitmap?.let { if (!it.isRecycled) it.recycle() }
            job.rawFrames.forEach { it.release() }
        }
    }

    private fun decrementSavingCount() {
        _uiState.update { state ->
            val remSaving = (state.captureProgress.savingCount - 1).coerceAtLeast(0)
            if (remSaving == 0 && !state.captureProgress.isCapturing) {
                state.copy(captureProgress = CaptureProgressState())
            } else {
                state.copy(
                    captureProgress = state.captureProgress.copy(
                        savingCount = remSaving,
                        statusText = if (remSaving > 0 && !state.captureProgress.isCapturing) {
                            "Saving $remSaving photo${if (remSaving > 1) "s" else ""}..."
                        } else {
                            state.captureProgress.statusText
                        }
                    )
                )
            }
        }
    }

    /**
     * Evaluates on the main thread whether the live scene during Auto Pixel Night Sight has become
     * bright enough to return to the daylight custom HDR+ engine.
     *
     * Priority:
     * 1. Hardware AE telemetry (`SENSOR_SENSITIVITY` + `SENSOR_EXPOSURE_TIME`) when exposed by the
     *    Extension HAL, combined with live TextureView frame luma via `LowLightDetector.computeSeverity`.
     * 2. Live rear-camera `TextureView` brightness (24x18 zero-alloc sample) with adaptive threshold
     *    + ambient lux jump fallback when the Extension HAL omits ISO/exposure keys.
     */
    private fun evaluateAutoPixelNightDaylightExit(): Boolean {
        val sample = cameraController.samplePreviewBrightness(activePreviewViewRef?.get())
        val (hwIso, hwExpNs) = cameraController.getLatestHardwareExposure()

        if (sample != null) {
            val (meanLuma, crushedPct) = sample
            lastAutoPixelNightExitLuma = meanLuma
            if (hwIso != null && hwIso > 0 && hwExpNs != null && hwExpNs > 0L) {
                val severity = LowLightDetector.computeSeverity(
                    meanLuma = meanLuma,
                    crushedShadowPercent = crushedPct,
                    sensorIso = hwIso,
                    exposureTimeNs = hwExpNs,
                )
                return severity < LowLightDetector.EXIT_LOW_LIGHT_THRESHOLD
            }
            if (meanLuma >= adaptiveAutoNightExitLuma && crushedPct <= 14f) {
                return true
            }
        }

        val lux = latestAmbientLux
        val baseLux = autoPixelNightEntryLux
        if (lux != null && baseLux != null) {
            val exitThreshold = maxOf(65f, baseLux * 2.2f + 35f)
            if (lux >= exitThreshold) {
                return true
            }
        }
        return false
    }

    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        activePreviewViewRef = WeakReference(previewView)
        val state = _uiState.value
        val requestedExtMode = state.desiredPixelExtensionMode
        val wantsNativeUltraHdr = state.wantsNativeUltraHdrCapture
        viewModelScope.launch {
            try {
                val caps = cameraController.bindCamera(
                    lifecycleOwner = lifecycleOwner,
                    previewView = previewView,
                    lensFacing = state.lensFacing,
                    preferredCameraId = if (requestedExtMode != PixelExtensionMode.NONE) null else state.preferredCameraId,
                    aspectRatioMode = state.aspectRatio,
                    flashMode = state.flashMode,
                    pixelCameraServicesEnabled = state.pixelCameraServicesEnabled,
                    requestedExtensionMode = requestedExtMode,
                    nativeUltraHdrRequested = wantsNativeUltraHdr,
                    faceDetectionEnabled = state.hdrConfig.faceDetectionEnabled,
                    isHistogramVisible = {
                        val s = _uiState.value
                        s.showHistogram && s.isAnalysisActiveInSession && !s.isSettingsSheetOpen && !s.isInspectorOpen
                    },
                    isAnalysisPaused = {
                        val s = _uiState.value
                        s.isSettingsSheetOpen || s.isInspectorOpen
                    },
                    onLowLightBoostStateChanged = { boosting ->
                        _uiState.update { it.copy(isLowLightBoostActive = boosting) }
                    },
                    onFacesDetected = { faces ->
                        _detectedFacesState.value = faces
                        if (_uiState.value.detectedFaceCount != faces.size) {
                            _uiState.update { it.copy(detectedFaceCount = faces.size) }
                        }
                    },
                    onLiveAnalysis = { analysis ->
                        _histogramState.value = analysis
                        val (hwIso, hwExpNs) = cameraController.getLatestHardwareExposure()
                        updateLowLightDetection(
                            meanLuma = analysis.meanLuma,
                            crushedShadowPercent = analysis.crushedShadowPercent,
                            clippedHighlightPercent = analysis.clippedHighlightPercent,
                            sensorIso = hwIso,
                            exposureTimeNs = hwExpNs,
                            faceMeanLuma = analysis.faceMeanLuma,
                        )
                    },
                )
                val extActive = caps.activeExtensionMode != PixelExtensionMode.NONE
                if (extActive && caps.activeExtensionMode == PixelExtensionMode.NIGHT) {
                    autoPixelNightEntryLux = null
                    latestAmbientLux = null
                }
                _uiState.update { current ->
                    val nextManual = if (extActive) {
                        current.manualSettings.copy(
                            iso = null,
                            shutterSpeedNs = null,
                            focusDistanceDiopter = null,
                            whiteBalanceKelvin = null,
                        )
                    } else {
                        current.manualSettings
                    }
                    // Preserve isLowLightDetected when entering NIGHT so Auto Night Sight does not oscillate!
                    val nextLowLightDetected = if (extActive && caps.activeExtensionMode != PixelExtensionMode.NIGHT) {
                        false
                    } else {
                        current.isLowLightDetected
                    }
                    current.copy(
                        pixelExtensionsSupported = caps.pixelExtensionsSupported,
                        supportedPixelExtensions = caps.supportedPixelExtensions,
                        hasCheckedPixelExtensions = true,
                        activePixelExtensionMode = caps.activeExtensionMode,
                        nativeUltraHdrSupported = caps.nativeUltraHdrSupported,
                        nativeUltraHdrActive = caps.nativeUltraHdrActive,
                        isAnalysisActiveInSession = caps.isImageAnalysisSupportedInSession,
                        isProPanelExpanded = if (extActive) false else current.isProPanelExpanded,
                        manualSettings = nextManual,
                        isLowLightDetected = nextLowLightDetected,
                        isBacklitDetected = if (extActive) false else current.isBacklitDetected,
                        telemetry = current.telemetry.copy(
                            rawSupported = caps.rawSupported,
                            manualControlHardwareSupported = caps.manualSensorSupported,
                            minEvIndex = caps.minEvIndex,
                            maxEvIndex = caps.maxEvIndex,
                            evStep = caps.evStep,
                            isoRange = caps.isoRange,
                            minFocusDiopter = caps.minFocusDiopter,
                            minZoomRatio = caps.minZoomRatio,
                            maxZoomRatio = caps.maxZoomRatio,
                            activeFocalLengthMm35 = caps.activeFocalLengthMm35,
                            activeAperture = caps.activeAperture,
                            lensOptions = caps.lensOptions,
                        )
                    )
                }
                if (state.pixelCameraServicesEnabled && !extActive && !caps.pixelExtensionsSupported && !hasShownNightOnlyNotice) {
                    hasShownNightOnlyNotice = true
                    showBanner("Pixel Night Sight unavailable on this lens — using custom Night Sight")
                }
                cameraController.setZoomRatio(state.currentZoomRatio)
                if (!extActive) {
                    cameraController.applyManualSettings(_uiState.value.manualSettings)
                }
                syncLowLightBoost()
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Failed to bind camera", e)
                showBanner("Camera initialization issue: ${e.localizedMessage ?: "Check camera hardware"}")
            }
        }
    }

    fun updateLowLightDetection(
        meanLuma: Float,
        crushedShadowPercent: Float,
        sensorIso: Int? = null,
        exposureTimeNs: Long? = null,
        clippedHighlightPercent: Float = 0f,
        faceMeanLuma: Float? = null,
    ) {
        val rawSeverity = LowLightDetector.computeSeverity(
            meanLuma = meanLuma,
            crushedShadowPercent = crushedShadowPercent,
            sensorIso = sensorIso,
            exposureTimeNs = exposureTimeNs,
        )
        val smoothed = if (smoothedLowLightSeverity < 0f) {
            rawSeverity
        } else {
            smoothedLowLightSeverity * 0.65f + rawSeverity * 0.35f
        }
        smoothedLowLightSeverity = smoothed

        val current = _uiState.value
        val nextDetected = LowLightDetector.nextLowLightActiveState(
            currentlyActive = current.isLowLightDetected,
            smoothedSeverity = smoothed,
        )
        val nextBacklit = LowLightDetector.isBacklitScene(
            clippedHighlightPercent = clippedHighlightPercent,
            crushedShadowPercent = crushedShadowPercent,
            meanLuma = meanLuma,
            faceMeanLuma = faceMeanLuma,
        )

        val effectiveSeverity = when {
            nextDetected -> smoothed
            current.nightSightMode == NightSightMode.ON -> smoothed.coerceAtLeast(0.45f)
            else -> smoothed
        }
        val caps = cameraController.getCapabilities()
        val plan = LowLightDetector.planLongExposure(
            severity = effectiveSeverity,
            meanLuma = meanLuma,
            currentIso = sensorIso,
            currentExposureNs = exposureTimeNs,
            isoRange = caps.isoRange,
            exposureTimeRangeNs = caps.exposureTimeRangeNs,
            clippedHighlightPercent = clippedHighlightPercent,
        )
        val estSec = (plan.estimatedTotalHoldMs / 1000f)

        val changedDetected = nextDetected != current.isLowLightDetected
        if (changedDetected) {
            val now = System.currentTimeMillis()
            if (nextDetected && lastAutoPixelNightExitMs > 0L && (now - lastAutoPixelNightExitMs) < 4500L) {
                // Re-entered low light quickly after a luma-only Pixel Night exit: raise exit luma floor
                // above Pixel Night's brightened preview level to prevent oscillation in dim rooms.
                adaptiveAutoNightExitLuma = maxOf(
                    adaptiveAutoNightExitLuma,
                    lastAutoPixelNightExitLuma + 0.14f,
                ).coerceAtMost(0.72f)
            } else if (!nextDetected) {
                daylightSteadySinceMs = now
            }
        } else if (!nextDetected &&
            adaptiveAutoNightExitLuma > DEFAULT_AUTO_NIGHT_EXIT_LUMA &&
            daylightSteadySinceMs > 0L &&
            (System.currentTimeMillis() - daylightSteadySinceMs) >= 5000L
        ) {
            adaptiveAutoNightExitLuma = DEFAULT_AUTO_NIGHT_EXIT_LUMA
        }
        val changedBacklit = nextBacklit != current.isBacklitDetected
        val changedEstimate = kotlin.math.abs(estSec - current.estimatedNightHoldSeconds) >= 0.25f
        val changedSeverity = kotlin.math.abs(smoothed - current.lowLightSeverity) >= 0.05f
        if (changedDetected || changedBacklit || changedEstimate || changedSeverity) {
            _uiState.update {
                it.copy(
                    isLowLightDetected = nextDetected,
                    isBacklitDetected = nextBacklit,
                    lowLightSeverity = smoothed,
                    estimatedNightHoldSeconds = estSec,
                )
            }
            if (changedDetected) {
                syncLowLightBoost()
            }
        }
    }

    fun setPixelCameraServicesEnabled(enabled: Boolean) {
        val current = _uiState.value
        if (current.pixelCameraServicesEnabled == enabled) return

        if (enabled) {
            hasShownNightOnlyNotice = false
            val nightSupportedOrUnknown = !current.hasCheckedPixelExtensions ||
                PixelExtensionMode.NIGHT in current.supportedPixelExtensions
            val willEnterExtensionNow = nightSupportedOrUnknown &&
                (current.nightSightMode == NightSightMode.ON || current.isAutoNightSightTriggered)
            val sanitizedManual = if (willEnterExtensionNow) {
                current.manualSettings.copy(
                    iso = null,
                    shutterSpeedNs = null,
                    focusDistanceDiopter = null,
                    whiteBalanceKelvin = null,
                )
            } else {
                current.manualSettings
            }
            _uiState.update { state ->
                state.copy(
                    pixelCameraServicesEnabled = true,
                    isProPanelExpanded = if (willEnterExtensionNow) false else state.isProPanelExpanded,
                    manualSettings = sanitizedManual,
                    preferredCameraId = if (willEnterExtensionNow) null else state.preferredCameraId,
                    isAnalysisActiveInSession = if (willEnterExtensionNow) false else state.isAnalysisActiveInSession,
                    isBacklitDetected = if (willEnterExtensionNow) false else state.isBacklitDetected,
                    isLowLightBoostActive = if (willEnterExtensionNow) false else state.isLowLightBoostActive,
                )
            }
            if (willEnterExtensionNow) {
                cameraController.applyManualSettings(sanitizedManual)
            }
            persistPrefs { it.copy(pixelCameraServicesEnabled = true) }
            when {
                current.hasCheckedPixelExtensions && !current.pixelExtensionsSupported -> {
                    showBanner("Pixel Night Sight unavailable on this lens — using custom engine")
                }
                willEnterExtensionNow -> {
                    showBanner("Pixel Night Sight active — Pro & custom HDR+ locked")
                }
                else -> {
                    showBanner("Pixel Night Sight enabled • Daylight & Backlight use custom HDR+")
                }
            }
        } else {
            hasShownNightOnlyNotice = false
            _uiState.update { state ->
                state.copy(
                    pixelCameraServicesEnabled = false,
                    activePixelExtensionMode = PixelExtensionMode.NONE,
                    isAnalysisActiveInSession = true,
                )
            }
            persistPrefs { it.copy(pixelCameraServicesEnabled = false) }
            showBanner("Pixel Camera Services off — Custom engine active")
        }
    }

    fun cycleNightSightMode() {
        val current = _uiState.value
        val next = when (current.nightSightMode) {
            NightSightMode.AUTO -> NightSightMode.ON
            NightSightMode.ON -> NightSightMode.OFF
            NightSightMode.OFF -> NightSightMode.AUTO
        }
        setNightSightMode(next)
        val pixelNightReady = current.pixelCameraServicesEnabled && current.isPixelNightOnlyDevice
        val msg = when (next) {
            NightSightMode.AUTO -> if (pixelNightReady) {
                "Night Sight: Auto (Pixel Night in low light)"
            } else {
                "Night Sight: Auto (activates in low light)"
            }
            NightSightMode.ON -> if (pixelNightReady) {
                "Night Sight: Always On (Pixel Night)"
            } else {
                "Night Sight: Always On (HDR disabled)"
            }
            NightSightMode.OFF -> if (pixelNightReady) {
                "Night Sight: Off • Daylight uses custom HDR+"
            } else {
                "Night Sight: Off"
            }
        }
        showBanner(msg)
    }

    fun setNightSightMode(mode: NightSightMode) {
        persistPrefs { it.copy(nightSightMode = mode) }
        if (mode == NightSightMode.ON && _uiState.value.estimatedNightHoldSeconds < 0.5f) {
            val caps = cameraController.getCapabilities()
            val plan = LowLightDetector.planLongExposure(
                severity = _uiState.value.lowLightSeverity.coerceAtLeast(0.50f),
                meanLuma = _histogramState.value.meanLuma,
                currentIso = cameraController.getLatestHardwareExposure().first,
                currentExposureNs = cameraController.getLatestHardwareExposure().second,
                isoRange = caps.isoRange,
                exposureTimeRangeNs = caps.exposureTimeRangeNs,
            )
            _uiState.update { it.copy(estimatedNightHoldSeconds = plan.estimatedTotalHoldMs / 1000f) }
        }
        syncLowLightBoost()
    }

    fun toggleNightSightQuick() {
        val current = _uiState.value
        val next = if (current.isNightSightActive) {
            NightSightMode.OFF
        } else {
            NightSightMode.AUTO
        }
        setNightSightMode(next)
        showBanner(if (next == NightSightMode.OFF) "Night Sight paused" else "Night Sight Auto enabled")
    }

    fun toggleBacklightModeQuick() {
        val current = _uiState.value.hdrConfig.backlightMode
        val next = when (current) {
            BacklightMode.AUTO -> BacklightMode.ON
            BacklightMode.ON -> BacklightMode.OFF
            BacklightMode.OFF -> BacklightMode.AUTO
        }
        setBacklightMode(next)
        val msg = when (next) {
            BacklightMode.AUTO -> "Backlight Photo: Auto (active when backlit)"
            BacklightMode.ON -> "Backlight Photo: Always On (anti-glare active)"
            BacklightMode.OFF -> "Backlight Photo: Off"
        }
        showBanner(msg)
    }

    fun setBacklightMode(mode: BacklightMode) {
        persistPrefs { it.copy(hdrConfig = it.hdrConfig.copy(backlightMode = mode)) }
    }

    private fun syncLowLightBoost() {
        val s = _uiState.value
        val shouldBoost = !s.isPixelServicesActive &&
            s.isNightSightActive &&
            s.flashMode == FlashMode.OFF &&
            !s.captureProgress.isCapturing
        cameraController.setLowLightBoostEnabled(shouldBoost)
    }

    fun cycleHdrMode() {
        if (_uiState.value.isNightSightActive) {
            showBanner("HDR disabled in Low Light mode")
            return
        }
        val next = when (_uiState.value.hdrMode) {
            HdrMode.HDR_PLUS_ENHANCED -> HdrMode.HDR_PLUS
            HdrMode.HDR_PLUS -> HdrMode.OFF
            HdrMode.OFF -> HdrMode.HDR_PLUS_ENHANCED
        }
        persistPrefs { it.copy(hdrMode = next) }
        showBanner("Computational Engine: ${next.label}")
    }

    fun setHdrMode(mode: HdrMode) {
        if (_uiState.value.isNightSightActive) {
            if (mode != HdrMode.OFF) {
                showBanner("HDR disabled in Low Light mode")
            }
            return
        }
        persistPrefs { it.copy(hdrMode = mode) }
    }

    fun setAspectRatio(ratio: AspectRatioMode) {
        persistPrefs { it.copy(aspectRatio = ratio) }
    }

    fun setTimerDuration(duration: TimerDuration) {
        _uiState.update { it.copy(timerDuration = duration) }
    }

    fun setFlashMode(flashMode: FlashMode) {
        _uiState.update { it.copy(flashMode = flashMode) }
        cameraController.updateFlashMode(flashMode)
        syncLowLightBoost()
    }

    fun toggleQuickSettings() {
        _uiState.update { it.copy(isQuickSettingsExpanded = !it.isQuickSettingsExpanded) }
    }

    fun setQuickSettingsExpanded(expanded: Boolean) {
        _uiState.update { it.copy(isQuickSettingsExpanded = expanded) }
    }

    fun selectToneTab(tab: TonePanelTab) {
        _uiState.update { it.copy(activeToneTab = tab) }
    }

    fun saveCurrentToneAsPreset(name: String) {
        val trimmed = name.trim().ifEmpty { "Look ${_uiState.value.tonePresets.size + 1}" }
        val m = _uiState.value.manualSettings
        val preset = TonePreset(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            shadowBoost = m.shadowBoost,
            highlightBoost = m.highlightBoost,
            contrastAdjust = m.contrastAdjust,
            vibrancyAdjust = m.vibrancyAdjust,
            createdAtMs = System.currentTimeMillis(),
        )
        _uiState.update { state ->
            state.copy(
                tonePresets = listOf(preset) + state.tonePresets,
                activePresetId = preset.id,
                activeToneTab = TonePanelTab.PRESETS,
            )
        }
        persistPrefs { prefs ->
            prefs.copy(
                tonePresets = listOf(preset) + prefs.tonePresets,
                activePresetId = preset.id,
            )
        }
        showBanner("Saved Look: $trimmed")
    }

    fun applyTonePreset(preset: TonePreset?) {
        if (preset == null) {
            _uiState.update { state ->
                state.copy(
                    activePresetId = null,
                    manualSettings = state.manualSettings.copy(
                        shadowBoost = 0f,
                        highlightBoost = 0f,
                        contrastAdjust = 0f,
                        vibrancyAdjust = 0f,
                    )
                )
            }
            persistPrefs { it.copy(activePresetId = null) }
        } else {
            _uiState.update { state ->
                state.copy(
                    activePresetId = preset.id,
                    manualSettings = state.manualSettings.copy(
                        shadowBoost = preset.shadowBoost,
                        highlightBoost = preset.highlightBoost,
                        contrastAdjust = preset.contrastAdjust,
                        vibrancyAdjust = preset.vibrancyAdjust,
                    )
                )
            }
            persistPrefs { it.copy(activePresetId = preset.id) }
        }
    }

    fun deleteTonePreset(presetId: String) {
        _uiState.update { state ->
            val nextPresets = state.tonePresets.filterNot { it.id == presetId }
            val nextActive = if (state.activePresetId == presetId) null else state.activePresetId
            state.copy(tonePresets = nextPresets, activePresetId = nextActive)
        }
        persistPrefs { prefs ->
            val nextPresets = prefs.tonePresets.filterNot { it.id == presetId }
            val nextActive = if (prefs.activePresetId == presetId) null else prefs.activePresetId
            prefs.copy(tonePresets = nextPresets, activePresetId = nextActive)
        }
    }

    fun toggleLooksBar() {
        _uiState.update {
            val opening = !it.isLooksBarExpanded
            it.copy(
                isLooksBarExpanded = opening,
                isProPanelExpanded = if (opening) false else it.isProPanelExpanded,
                isTonePanelExpanded = if (opening) false else it.isTonePanelExpanded,
                isQuickSettingsExpanded = false,
            )
        }
    }

    fun toggleProPanel() {
        if (_uiState.value.isPixelServicesLocked) {
            showBanner("Pro controls locked by Pixel Camera Services")
            return
        }
        _uiState.update {
            val opening = !it.isProPanelExpanded
            it.copy(
                isProPanelExpanded = opening,
                isTonePanelExpanded = if (opening) false else it.isTonePanelExpanded,
                isLooksBarExpanded = if (opening) false else it.isLooksBarExpanded,
                isQuickSettingsExpanded = false,
            )
        }
    }

    fun toggleTonePanel() {
        _uiState.update {
            val opening = !it.isTonePanelExpanded
            it.copy(
                isTonePanelExpanded = opening,
                isProPanelExpanded = if (opening) false else it.isProPanelExpanded,
                isLooksBarExpanded = if (opening) false else it.isLooksBarExpanded,
                isQuickSettingsExpanded = false,
            )
        }
    }

    fun selectProTab(tab: ProDialTab) {
        _uiState.update { it.copy(activeProTab = tab) }
    }

    fun selectLens(lens: com.example.camera.model.LensOption) {
        val currentState = _uiState.value
        // Extension sessions bind to the logical multi-camera and switch lenses via smooth zoomRatio
        val targetCameraId = if (currentState.isPixelServicesLocked) null else lens.cameraId
        val needsRebind = targetCameraId != currentState.preferredCameraId
        if (needsRebind) {
            _uiState.update {
                it.copy(
                    preferredCameraId = targetCameraId,
                    activeLensId = lens.id,
                    currentZoomRatio = lens.zoomRatio,
                    focusReticle = null,
                )
            }
        } else {
            val applied = cameraController.setZoomRatio(lens.zoomRatio)
            _uiState.update {
                it.copy(
                    activeLensId = lens.id,
                    currentZoomRatio = applied,
                )
            }
        }
    }

    fun onPinchZoom(zoomFactor: Float) {
        val current = _uiState.value
        val minZ = current.telemetry.minZoomRatio
        val maxZ = current.telemetry.maxZoomRatio
        val target = (current.currentZoomRatio * zoomFactor).coerceIn(minZ, maxZ)
        val applied = cameraController.setZoomRatio(target)
        _uiState.update {
            it.copy(
                currentZoomRatio = applied,
                isZoomRulerVisible = true,
            )
        }
        scheduleZoomRulerHide()
    }

    fun onZoomSliderChange(zoomRatio: Float) {
        val applied = cameraController.setZoomRatio(zoomRatio)
        _uiState.update {
            it.copy(
                currentZoomRatio = applied,
                isZoomRulerVisible = true,
            )
        }
        scheduleZoomRulerHide()
    }

    fun toggleZoomRuler() {
        val next = !_uiState.value.isZoomRulerVisible
        _uiState.update { it.copy(isZoomRulerVisible = next) }
        if (next) scheduleZoomRulerHide()
    }

    private fun scheduleZoomRulerHide() {
        zoomRulerHideJob?.cancel()
        zoomRulerHideJob = viewModelScope.launch {
            delay(2400L)
            _uiState.update { it.copy(isZoomRulerVisible = false) }
        }
    }

    fun cycleFlashMode() {
        val next = when (_uiState.value.flashMode) {
            FlashMode.OFF -> FlashMode.AUTO
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.OFF
        }
        _uiState.update { it.copy(flashMode = next) }
        cameraController.updateFlashMode(next)
        syncLowLightBoost()
    }

    fun cycleTimerDuration() {
        val next = when (_uiState.value.timerDuration) {
            TimerDuration.OFF -> TimerDuration.SEC_3
            TimerDuration.SEC_3 -> TimerDuration.SEC_10
            TimerDuration.SEC_10 -> TimerDuration.OFF
        }
        _uiState.update { it.copy(timerDuration = next) }
    }

    fun toggleAspectRatio() {
        val next = if (_uiState.value.aspectRatio == AspectRatioMode.RATIO_4_3) {
            AspectRatioMode.RATIO_16_9
        } else {
            AspectRatioMode.RATIO_4_3
        }
        persistPrefs { it.copy(aspectRatio = next) }
    }

    fun switchLensFacing() {
        val next = if (_uiState.value.lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        _uiState.update {
            it.copy(
                lensFacing = next,
                preferredCameraId = null,
                activeLensId = if (next == CameraSelector.LENS_FACING_FRONT) "front_1x" else "main_1x",
                currentZoomRatio = 1f,
                isZoomRulerVisible = false,
                focusReticle = null,
            )
        }
    }

    fun onTapViewfinder(
        previewView: PreviewView,
        xPx: Float,
        yPx: Float,
        viewWidth: Float,
        viewHeight: Float,
    ) {
        if (viewWidth <= 0f || viewHeight <= 0f) return
        activePreviewViewRef = WeakReference(previewView)
        val normX = (xPx / viewWidth).coerceIn(0f, 1f)
        val normY = (yPx / viewHeight).coerceIn(0f, 1f)
        val wasLocked = _uiState.value.focusReticle?.isAeAfLocked == true
        val manualFocusActive = _uiState.value.manualSettings.focusDistanceDiopter != null

        _uiState.update {
            it.copy(
                isQuickSettingsExpanded = false,
                focusReticle = FocusReticleState(
                    xPx = xPx,
                    yPx = yPx,
                    normX = normX,
                    normY = normY,
                    visible = true,
                    isFocused = if (manualFocusActive) true else null,
                    isAeAfLocked = false,
                )
            )
        }
        if (wasLocked) {
            showBanner("AE/AF Unlocked")
        }
        focusHideJob?.cancel()
        focusHideJob = viewModelScope.launch {
            val focused = cameraController.triggerTapToFocus(
                previewView = previewView,
                x = xPx,
                y = yPx,
                manualFocusActive = manualFocusActive,
                lockAeAf = false,
            )
            _uiState.update { state ->
                val cur = state.focusReticle
                if (cur != null && cur.normX == normX && cur.normY == normY && !cur.isAeAfLocked) {
                    state.copy(focusReticle = cur.copy(isFocused = focused))
                } else {
                    state
                }
            }
            delay(3200L)
            if (_uiState.value.focusReticle?.isAeAfLocked == false) {
                cameraController.cancelFocusLock()
            }
            _uiState.update { state ->
                val cur = state.focusReticle
                if (cur != null && !cur.isAeAfLocked) {
                    state.copy(focusReticle = cur.copy(visible = false))
                } else {
                    state
                }
            }
        }
    }

    fun onLongPressViewfinder(
        previewView: PreviewView,
        xPx: Float,
        yPx: Float,
        viewWidth: Float,
        viewHeight: Float,
    ) {
        if (viewWidth <= 0f || viewHeight <= 0f) return
        activePreviewViewRef = WeakReference(previewView)
        val normX = (xPx / viewWidth).coerceIn(0f, 1f)
        val normY = (yPx / viewHeight).coerceIn(0f, 1f)
        val manualFocusActive = _uiState.value.manualSettings.focusDistanceDiopter != null

        focusHideJob?.cancel()
        _uiState.update {
            it.copy(
                isQuickSettingsExpanded = false,
                focusReticle = FocusReticleState(
                    xPx = xPx,
                    yPx = yPx,
                    normX = normX,
                    normY = normY,
                    visible = true,
                    isFocused = if (manualFocusActive) true else null,
                    isAeAfLocked = true,
                )
            )
        }
        showBanner("AE/AF Locked • Tap anywhere to unlock")

        focusHideJob = viewModelScope.launch {
            val focused = cameraController.triggerTapToFocus(
                previewView = previewView,
                x = xPx,
                y = yPx,
                manualFocusActive = manualFocusActive,
                lockAeAf = true,
            )
            _uiState.update { state ->
                val cur = state.focusReticle
                if (cur != null && cur.normX == normX && cur.normY == normY && cur.isAeAfLocked) {
                    state.copy(focusReticle = cur.copy(isFocused = focused))
                } else {
                    state
                }
            }
        }
    }

    fun unlockAeAf() {
        focusHideJob?.cancel()
        cameraController.cancelFocusLock()
        _uiState.update { state ->
            state.copy(focusReticle = state.focusReticle?.copy(isAeAfLocked = false, visible = false))
        }
        showBanner("AE/AF Unlocked")
    }

    fun onReticleEvDragStart() {
        focusHideJob?.cancel()
    }

    fun onReticleEvChange(ev: Float) {
        updateExposureEv(ev)
    }

    fun onReticleEvDragEnd() {
        val currentReticle = _uiState.value.focusReticle ?: return
        if (currentReticle.isAeAfLocked) return
        focusHideJob?.cancel()
        focusHideJob = viewModelScope.launch {
            delay(3200L)
            if (_uiState.value.focusReticle?.isAeAfLocked == false) {
                cameraController.cancelFocusLock()
            }
            _uiState.update { state ->
                val cur = state.focusReticle
                if (cur != null && !cur.isAeAfLocked) {
                    state.copy(focusReticle = cur.copy(visible = false))
                } else {
                    state
                }
            }
        }
    }

    fun updateExposureEv(ev: Float) {
        updateManual { it.copy(exposureCompensationEv = ev.coerceIn(-3.0f, 3.0f)) }
    }

    fun updateShadowBoost(shadow: Float) {
        updateToneOnly { it.copy(shadowBoost = shadow.coerceIn(-1.0f, 1.0f)) }
    }

    fun updateHighlightBoost(highlight: Float) {
        updateToneOnly { it.copy(highlightBoost = highlight.coerceIn(-1.0f, 1.0f)) }
    }

    fun updateContrastAdjust(contrast: Float) {
        updateToneOnly { it.copy(contrastAdjust = contrast.coerceIn(-1.0f, 1.0f)) }
    }

    fun updateVibrancyAdjust(vibrancy: Float) {
        updateToneOnly { it.copy(vibrancyAdjust = vibrancy.coerceIn(-1.0f, 1.0f)) }
    }

    fun resetToneAdjustments() {
        updateToneOnly {
            it.copy(
                shadowBoost = 0f,
                highlightBoost = 0f,
                contrastAdjust = 0f,
                vibrancyAdjust = 0f,
            )
        }
    }

    fun updateWhiteBalanceKelvin(kelvin: Int?) {
        if (_uiState.value.isPixelServicesLocked) {
            showBanner("White balance locked by Pixel Camera Services")
            return
        }
        updateManual { it.copy(whiteBalanceKelvin = kelvin?.coerceIn(2500, 8500)) }
    }

    fun updateIso(iso: Int?) {
        if (_uiState.value.isPixelServicesLocked) {
            showBanner("ISO locked by Pixel Camera Services")
            return
        }
        updateManual { it.copy(iso = iso) }
    }

    fun updateShutterSpeedNs(ns: Long?) {
        if (_uiState.value.isPixelServicesLocked) {
            showBanner("Shutter speed locked by Pixel Camera Services")
            return
        }
        updateManual { it.copy(shutterSpeedNs = ns) }
    }

    fun updateFocusDistance(diopter: Float?) {
        if (_uiState.value.isPixelServicesLocked) {
            showBanner("Manual focus locked by Pixel Camera Services")
            return
        }
        updateManual { it.copy(focusDistanceDiopter = diopter) }
    }

    fun resetManualSettings() {
        val reset = ManualExposureSettings()
        _uiState.update { it.copy(manualSettings = reset) }
        cameraController.applyManualSettings(reset)
        syncLowLightBoost()
        showBanner("Pro exposure reset to Auto")
    }

    private fun updateToneOnly(transform: (ManualExposureSettings) -> ManualExposureSettings) {
        val updated = transform(_uiState.value.manualSettings)
        _uiState.update {
            it.copy(
                manualSettings = updated,
                activePresetId = null,
            )
        }
    }

    private fun updateManual(transform: (ManualExposureSettings) -> ManualExposureSettings) {
        val updated = transform(_uiState.value.manualSettings)
        _uiState.update { it.copy(manualSettings = updated) }
        cameraController.applyManualSettings(updated)
        syncLowLightBoost()
    }

    fun setSettingsSheetOpen(open: Boolean) {
        _uiState.update {
            it.copy(
                isSettingsSheetOpen = open,
                isQuickSettingsExpanded = false,
            )
        }
    }

    fun setInspectorOpen(open: Boolean) {
        _uiState.update { it.copy(isInspectorOpen = open) }
    }

    fun selectCapturedPhoto(index: Int) {
        _uiState.update {
            it.copy(selectedPhotoIndex = index.coerceIn(0, (it.capturedPhotos.size - 1).coerceAtLeast(0)))
        }
    }

    fun updatePreferences(transform: (PersistedCameraPreferences) -> PersistedCameraPreferences) {
        val prevFaceDetect = _uiState.value.hdrConfig.faceDetectionEnabled
        persistPrefs(transform)
        val nextFaceDetect = _uiState.value.hdrConfig.faceDetectionEnabled
        if (nextFaceDetect != prevFaceDetect) {
            cameraController.setFaceDetectionEnabled(nextFaceDetect)
            if (!nextFaceDetect) {
                _detectedFacesState.value = emptyList()
                _uiState.update { it.copy(detectedFaceCount = 0) }
            }
        }
    }

    fun capturePhoto() {
        val currentProgress = _uiState.value.captureProgress
        if (currentProgress.isCapturing || captureJob?.isActive == true) return
        if (currentProgress.savingCount >= MAX_PENDING_RENDERS) {
            showBanner("Processing ${currentProgress.savingCount} photos — wait a moment")
            return
        }
        captureJob = viewModelScope.launch {
            val timerSec = _uiState.value.timerDuration.seconds
            if (timerSec > 0) {
                for (rem in timerSec downTo 1) {
                    _uiState.update { it.copy(countdownSecondsLeft = rem) }
                    delay(1000L)
                }
                _uiState.update { it.copy(countdownSecondsLeft = 0) }
            }

            val snapshot = _uiState.value
            val usePixelExtensions = snapshot.isPixelServicesActive &&
                snapshot.activePixelExtensionMode != PixelExtensionMode.NONE
            val activeExtMode = snapshot.activePixelExtensionMode
            val useNightSight = if (usePixelExtensions) {
                activeExtMode == PixelExtensionMode.NIGHT
            } else {
                snapshot.isNightSightActive
            }
            val effectiveHdrMode = snapshot.effectiveHdrMode
            val effectiveHdrConfig = snapshot.hdrConfig.copy(
                ultraHdrEnabled = snapshot.effectiveUltraHdrEnabled,
            )
            val targetFrames = when (effectiveHdrMode) {
                HdrMode.OFF -> 1
                HdrMode.HDR_PLUS -> 2
                HdrMode.HDR_PLUS_ENHANCED -> effectiveHdrConfig.burstFrameCount.coerceIn(2, 5)
            }

            _uiState.update { state ->
                state.copy(
                    isQuickSettingsExpanded = false,
                    captureProgress = state.captureProgress.copy(
                        isCapturing = true,
                        isNightSightCapture = useNightSight,
                        holdSecondsRemaining = if (useNightSight && !usePixelExtensions) {
                            state.estimatedNightHoldSeconds.coerceAtLeast(1.4f)
                        } else 0f,
                        progress = if (useNightSight) 0.04f else 0.12f,
                        statusText = when {
                            usePixelExtensions -> "Capturing ${activeExtMode.badge}..."
                            useNightSight -> "Hold still • Night Sight"
                            targetFrames > 1 -> "${effectiveHdrMode.badge} burst..."
                            else -> "Capturing..."
                        }
                    )
                )
            }

            var capturedFrames: List<RawCapturedFrame> = emptyList()
            var handedOffToQueue = false
            try {
                // Snapshot tracked face ROIs right at shutter press BEFORE the multi-frame burst runs
                // so lowering the phone or subject motion after capture never yields stale/empty boxes.
                val preCaptureFaces = if (effectiveHdrConfig.faceDetectionEnabled) {
                    cameraController.getCaptureFaces(effectiveHdrConfig.mirrorFrontCamera)
                } else emptyList()

                val activeLookName = snapshot.activePreset?.name
                    ?: if (snapshot.manualSettings.isAnyToneOverride) "Custom Tone" else "Default"
                val focusNorm = snapshot.focusReticle?.let { it.normX to it.normY }
                val latestAnalysis = _histogramState.value
                val latestLuma = latestAnalysis.meanLuma
                val wbUsed = snapshot.manualSettings.whiteBalanceKelvin ?: 5400

                val isoUsed: Int
                val shutterUsed: String
                val totalExposureSeconds: Float

                if (usePixelExtensions) {
                    capturedFrames = cameraController.captureExtensionSinglePhoto(
                        mirrorFrontCamera = effectiveHdrConfig.mirrorFrontCamera,
                        previewView = activePreviewViewRef?.get(),
                        isAeAfLocked = snapshot.focusReticle?.isAeAfLocked == true,
                        onCaptureProgress = { pct ->
                            _uiState.update { state ->
                                state.copy(
                                    captureProgress = state.captureProgress.copy(
                                        isCapturing = true,
                                        isNightSightCapture = useNightSight,
                                        progress = (0.15f + (pct / 100f) * 0.70f).coerceIn(0.15f, 0.90f),
                                        statusText = "${activeExtMode.badge} $pct%",
                                    )
                                )
                            }
                        }
                    )
                    _uiState.update { it.copy(shutterFlashTick = System.currentTimeMillis()) }
                    val (hwIso, hwExpNs) = cameraController.getLatestHardwareExposure()
                    isoUsed = hwIso ?: estimateAutoIso(latestLuma)
                    shutterUsed = if (hwExpNs != null && hwExpNs > 0L) {
                        val sec = hwExpNs / 1_000_000_000.0
                        if (sec >= 0.5) String.format("%.1f\"", sec)
                        else "1/${(1.0 / sec).roundToInt().coerceAtLeast(1)}s"
                    } else {
                        "Auto"
                    }
                    totalExposureSeconds = 0f
                } else if (useNightSight) {
                    val nightResult = cameraController.captureLowLightBurst(
                        lowLightSeverity = snapshot.lowLightSeverity.coerceAtLeast(0.45f),
                        meanLuma = latestLuma,
                        clippedHighlightPercent = latestAnalysis.clippedHighlightPercent,
                        manualSettings = snapshot.manualSettings,
                        mirrorFrontCamera = snapshot.hdrConfig.mirrorFrontCamera,
                        previewView = activePreviewViewRef?.get(),
                        isAeAfLocked = snapshot.focusReticle?.isAeAfLocked == true,
                        onHoldProgress = { progress, framesCaptured, total, remSec ->
                            val remLabel = if (remSec >= 0.2f) {
                                String.format("Hold still • %.1fs", remSec)
                            } else {
                                "Night Sight $framesCaptured/$total"
                            }
                            _uiState.update { state ->
                                state.copy(
                                    captureProgress = state.captureProgress.copy(
                                        isCapturing = true,
                                        isNightSightCapture = true,
                                        holdSecondsRemaining = remSec,
                                        progress = progress,
                                        statusText = remLabel,
                                    )
                                )
                            }
                        }
                    )
                    capturedFrames = nightResult.frames
                    _uiState.update { it.copy(shutterFlashTick = System.currentTimeMillis()) }
                    isoUsed = nightResult.isoUsed
                    totalExposureSeconds = nightResult.totalHoldMs / 1000f
                    val perFrameSec = nightResult.perFrameExposureNs / 1_000_000_000.0
                    val perFrameLabel = if (perFrameSec >= 0.5) {
                        String.format("%.1f\"", perFrameSec)
                    } else {
                        "1/${(1.0 / perFrameSec.coerceAtLeast(0.001)).roundToInt().coerceAtLeast(1)}s"
                    }
                    shutterUsed = String.format("%.1f\" (%d×%s)", totalExposureSeconds, capturedFrames.size, perFrameLabel)
                } else {
                    capturedFrames = cameraController.captureHdrBurst(
                        hdrMode = effectiveHdrMode,
                        requestedFrameCount = targetFrames,
                        manualSettings = snapshot.manualSettings,
                        mirrorFrontCamera = effectiveHdrConfig.mirrorFrontCamera,
                        isBacklit = snapshot.isBacklightActive,
                        clippedHighlightPercent = latestAnalysis.clippedHighlightPercent,
                        crushedShadowPercent = latestAnalysis.crushedShadowPercent,
                        meanLuma = latestLuma,
                        faceMeanLuma = latestAnalysis.faceMeanLuma,
                        previewView = activePreviewViewRef?.get(),
                        isAeAfLocked = snapshot.focusReticle?.isAeAfLocked == true,
                        onFrameCaptured = { count, total ->
                            val frac = 0.15f + (count.toFloat() / total) * 0.45f
                            _uiState.update { state ->
                                state.copy(
                                    shutterFlashTick = if (count == 1) System.currentTimeMillis() else state.shutterFlashTick,
                                    captureProgress = state.captureProgress.copy(
                                        isCapturing = true,
                                        isNightSightCapture = false,
                                        progress = frac,
                                        statusText = if (total > 1) "${effectiveHdrMode.badge} $count/$total" else "Saving..."
                                    )
                                )
                            }
                        }
                    )
                    val (hwIso, hwExpNs) = cameraController.getLatestHardwareExposure()
                    isoUsed = snapshot.manualSettings.iso ?: hwIso ?: estimateAutoIso(latestLuma)
                    shutterUsed = when {
                        snapshot.manualSettings.shutterSpeedNs != null -> snapshot.manualSettings.formattedShutter()
                        hwExpNs != null && hwExpNs > 0L -> {
                            val sec = hwExpNs / 1_000_000_000.0
                            if (sec >= 0.5) String.format("%.1f\"", sec)
                            else "1/${(1.0 / sec).roundToInt().coerceAtLeast(1)}s"
                        }
                        else -> estimateAutoShutterLabel(latestLuma)
                    }
                    totalExposureSeconds = 0f
                }

                val captureFaces = if (effectiveHdrConfig.faceDetectionEnabled) {
                    preCaptureFaces.ifEmpty {
                        cameraController.getCaptureFaces(effectiveHdrConfig.mirrorFrontCamera)
                    }
                } else emptyList()
                val job = PendingRenderJob(
                    rawFrames = capturedFrames,
                    cameraMode = snapshot.cameraMode,
                    hdrMode = effectiveHdrMode,
                    manualSettings = snapshot.manualSettings,
                    hdrConfig = effectiveHdrConfig,
                    activeLookName = activeLookName,
                    focusNorm = focusNorm,
                    isoUsed = isoUsed,
                    shutterUsed = shutterUsed,
                    wbUsed = wbUsed,
                    isNightSight = useNightSight,
                    lowLightSeverity = snapshot.lowLightSeverity.coerceAtLeast(if (useNightSight) 0.45f else 0f),
                    totalExposureSeconds = totalExposureSeconds,
                    isPixelExtensionCapture = usePixelExtensions,
                    pixelExtensionMode = activeExtMode,
                    isBacklightActive = snapshot.isBacklightActive,
                    detectedFaces = captureFaces,
                )

                val sendResult = renderQueue.trySend(job)
                if (sendResult.isSuccess) {
                    handedOffToQueue = true
                    _uiState.update { state ->
                        val nextSaving = state.captureProgress.savingCount + 1
                        state.copy(
                            captureProgress = CaptureProgressState(
                                isCapturing = false,
                                isNightSightCapture = false,
                                savingCount = nextSaving,
                                progress = 0.72f,
                                statusText = if (nextSaving > 1) {
                                    "Saving $nextSaving photos..."
                                } else if (usePixelExtensions) {
                                    "Saving ${activeExtMode.badge}..."
                                } else if (useNightSight) {
                                    "Processing Night Sight..."
                                } else if (capturedFrames.size > 1) {
                                    "Saving ${effectiveHdrMode.badge}..."
                                } else {
                                    "Saving photo..."
                                }
                            )
                        )
                    }
                } else {
                    _uiState.update { state ->
                        state.copy(
                            captureProgress = state.captureProgress.copy(
                                isCapturing = false,
                                isNightSightCapture = false,
                            )
                        )
                    }
                    showBanner("Processing queue full — wait a moment")
                }
            } catch (ce: CancellationException) {
                _uiState.update { state ->
                    state.copy(
                        captureProgress = state.captureProgress.copy(
                            isCapturing = false,
                            isNightSightCapture = false,
                        ),
                        countdownSecondsLeft = 0,
                    )
                }
                throw ce
            } catch (t: Throwable) {
                Log.e("CameraViewModel", "HDR+ capture failed", t)
                if (t is OutOfMemoryError) System.gc()
                _uiState.update { state ->
                    state.copy(
                        captureProgress = state.captureProgress.copy(
                            isCapturing = false,
                            isNightSightCapture = false,
                        ),
                        countdownSecondsLeft = 0,
                    )
                }
                showBanner("Capture error: ${t.localizedMessage ?: "Retry"}")
            } finally {
                if (!handedOffToQueue) {
                    capturedFrames.forEach { it.release() }
                }
            }
        }
    }

    fun deleteCapturedPhoto(record: CapturedPhotoRecord) {
        viewModelScope.launch {
            val deleted = mediaStoreRepository.deletePhoto(record.uriString)
            if (deleted) {
                _uiState.update { state ->
                    val remaining = state.capturedPhotos.filterNot { it.uriString == record.uriString }
                    state.copy(
                        capturedPhotos = remaining,
                        selectedPhotoIndex = 0,
                        latestGalleryThumbnailUri = remaining.firstOrNull()?.uriString
                            ?: mediaStoreRepository.queryLatestMediaUri(),
                        isInspectorOpen = remaining.isNotEmpty(),
                    )
                }
            }
        }
    }

    private fun persistPrefs(transform: (PersistedCameraPreferences) -> PersistedCameraPreferences) {
        val current = if (prefsLoaded) {
            cachedPrefs
        } else {
            val s = _uiState.value
            PersistedCameraPreferences(
                hdrMode = s.hdrMode,
                nightSightMode = s.nightSightMode,
                pixelCameraServicesEnabled = s.pixelCameraServicesEnabled,
                tonePresets = s.tonePresets,
                activePresetId = s.activePresetId,
                gridOverlay = s.gridOverlay,
                aspectRatio = s.aspectRatio,
                showHistogram = s.showHistogram,
                showHorizonLevel = s.showHorizonLevel,
                showFocusPeaking = s.showFocusPeaking,
                showZebraHighlights = s.showZebraHighlights,
                hdrConfig = s.hdrConfig,
            )
        }
        val next = transform(current)
        cachedPrefs = next
        prefsLoaded = true
        _uiState.update { state ->
            state.copy(
                hdrMode = next.hdrMode,
                nightSightMode = next.nightSightMode,
                pixelCameraServicesEnabled = next.pixelCameraServicesEnabled,
                tonePresets = next.tonePresets,
                activePresetId = next.activePresetId,
                gridOverlay = next.gridOverlay,
                aspectRatio = next.aspectRatio,
                showHistogram = next.showHistogram,
                showHorizonLevel = next.showHorizonLevel,
                showFocusPeaking = next.showFocusPeaking,
                showZebraHighlights = next.showZebraHighlights,
                hdrConfig = next.hdrConfig,
            )
        }
        syncLowLightBoost()
        persistPrefsJob?.cancel()
        persistPrefsJob = viewModelScope.launch(Dispatchers.IO) {
            delay(220L)
            val snapshotToWrite = cachedPrefs
            settingsDataStore.update { snapshotToWrite }
        }
    }

    private fun showBanner(message: String) {
        _uiState.update { it.copy(statusBannerMessage = message) }
        bannerHideJob?.cancel()
        bannerHideJob = viewModelScope.launch {
            delay(2600L)
            _uiState.update { it.copy(statusBannerMessage = null) }
        }
    }

    private fun estimateAutoIso(meanLuma: Float): Int {
        val raw = (1600f * (1f - meanLuma).coerceIn(0.05f, 0.95f)).roundToInt()
        return ((raw / 50).coerceIn(1, 64)) * 50
    }

    private fun estimateAutoShutterLabel(meanLuma: Float): String {
        return when {
            meanLuma > 0.72f -> "1/1000s"
            meanLuma > 0.52f -> "1/250s"
            meanLuma > 0.34f -> "1/125s"
            else -> "1/30s"
        }
    }

    override fun onCleared() {
        super.onCleared()
        renderQueue.close()
        while (true) {
            val pending = renderQueue.tryReceive().getOrNull() ?: break
            pending.rawFrames.forEach { it.release() }
        }
        cameraController.release()
    }

    companion object {
        private const val MAX_PENDING_RENDERS = 4
        private const val DEFAULT_AUTO_NIGHT_EXIT_LUMA = 0.36f
    }
}
