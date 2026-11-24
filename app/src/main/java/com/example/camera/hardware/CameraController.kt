package com.example.camera.hardware

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.RggbChannelVector
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.lang.ref.WeakReference
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalZeroShutterLag
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.LowLightBoostState
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionSessionConfig
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.camera.model.AspectRatioMode
import com.example.camera.model.DetectedFace
import com.example.camera.model.FaceCoordinateMapper
import com.example.camera.model.FlashMode
import com.example.camera.model.HdrMode
import com.example.camera.model.KelvinColorMath
import com.example.camera.model.LensOption
import com.example.camera.model.LowLightDetector
import com.example.camera.model.ManualExposureSettings
import com.example.camera.model.PixelExtensionMode
import com.example.camera.processing.LiveFrameAnalysisResult
import com.example.camera.processing.LiveFrameAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.camera.camera2.interop.Camera2Interop
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Compact captured frame handed off to the serial HDR+ render worker.
 * Keeping compressed JPEG bytes (~1.5-3MB) instead of uncompressed 20MB ARGB_8888 Bitmaps
 * prevents OutOfMemoryError when snapping multiple photos while earlier renders are still running.
 */
sealed interface RawCapturedFrame {
    val exposureBiasEv: Float
    fun release()

    class Jpeg(
        val bytes: ByteArray,
        val rotationDegrees: Int,
        val mirrorHorizontally: Boolean,
        override val exposureBiasEv: Float = 0f,
        val isNativeUltraHdr: Boolean = false,
    ) : RawCapturedFrame {
        override fun release() = Unit
    }

    class DecodedBitmap(
        val bitmap: Bitmap,
        override val exposureBiasEv: Float = 0f,
    ) : RawCapturedFrame {
        override fun release() {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}

data class LowLightCaptureResult(
    val frames: List<RawCapturedFrame>,
    val perFrameExposureNs: Long,
    val isoUsed: Int,
    val totalHoldMs: Long,
)

data class HardwareCapabilities(
    val rawSupported: Boolean = false,
    val manualSensorSupported: Boolean = false,
    val zslSupported: Boolean = false,
    val autoFocusSupported: Boolean = true,
    val lowLightBoostSupported: Boolean = false,
    val postRawBoostSupported: Boolean = false,
    val oisSupported: Boolean = false,
    val minimalNoiseReductionSupported: Boolean = false,
    val pixelExtensionsSupported: Boolean = false,
    val supportedPixelExtensions: Set<PixelExtensionMode> = emptySet(),
    val activeExtensionMode: PixelExtensionMode = PixelExtensionMode.NONE,
    val nativeUltraHdrSupported: Boolean = false,
    val nativeUltraHdrActive: Boolean = false,
    val isImageAnalysisSupportedInSession: Boolean = true,
    val minEvIndex: Int = -6,
    val maxEvIndex: Int = 6,
    val evStep: Float = 0.33f,
    val isoRange: IntRange = 50..6400,
    val exposureTimeRangeNs: LongRange = 100_000L..1_000_000_000L,
    val lowestAeFpsRange: Range<Int>? = null,
    val minFocusDiopter: Float = 10f,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 8f,
    val activeFocalLengthMm35: Int = 24,
    val activeAperture: Float? = null,
    val faceDetectMode: Int = CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF,
    val sensorOrientation: Int = 90,
    val sensorActiveArray: Rect? = null,
    val lensOptions: List<LensOption> = listOf(
        LensOption(id = "wide_1x", label = "1x", zoomRatio = 1f, focalLengthMm35 = 24),
        LensOption(id = "tele_2x", label = "2", zoomRatio = 2f, focalLengthMm35 = 48),
    ),
)

class CameraController(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private val extensionSupportCache = ConcurrentHashMap<Int, Set<PixelExtensionMode>>()
    private var boundCamera: Camera? = null
    private var previewUseCase: Preview? = null
    private var imageCaptureUseCase: ImageCapture? = null
    private var imageAnalysisUseCase: ImageAnalysis? = null

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val captureMutex = Mutex()
    private var previewProbeBitmap: Bitmap? = null
    private val previewProbePixels = IntArray(PREVIEW_PROBE_W * PREVIEW_PROBE_H)

    private var currentLensFacing = CameraSelector.LENS_FACING_BACK
    private var currentCapabilities = HardwareCapabilities()

    @Volatile
    private var lastAfState: Int = CaptureResult.CONTROL_AF_STATE_INACTIVE
    @Volatile
    private var lastSensorIso: Int? = null
    @Volatile
    private var lastPostRawBoost: Int = 100
    @Volatile
    private var lastExposureTimeNs: Long? = null
    @Volatile
    private var lowLightBoostEnabled: Boolean = false
    @Volatile
    private var lastTapAfTimestampMs: Long = 0L
    @Volatile
    private var lastTapFocusConvergedMs: Long = 0L
    @Volatile
    private var tapFocusInFlight: Boolean = false
    @Volatile
    private var hasActiveFocusMetering: Boolean = false
    @Volatile
    private var hasActiveCamera2Overrides: Boolean = false
    @Volatile
    private var lastAppliedEvIndex: Int? = null
    @Volatile
    private var activeFaceDetectMode: Int = CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
    @Volatile
    private var activeSensorOrientation: Int = 90
    @Volatile
    private var activeSensorArray: Rect? = null
    @Volatile
    private var activeDisplayRotation: Int = Surface.ROTATION_0
    @Volatile
    private var boundPreviewViewRef: WeakReference<PreviewView>? = null
    @Volatile
    private var activeAspectRatioMode: AspectRatioMode = AspectRatioMode.RATIO_4_3
    @Volatile
    private var activeFaceDetectEnabled: Boolean = true
    @Volatile
    private var lastFaceEmitTimeMs: Long = 0L
    @Volatile
    private var smoothedFaces: List<DetectedFace> = emptyList()
    @Volatile
    private var onFacesDetectedCallback: ((List<DetectedFace>) -> Unit)? = null

    fun getLatestHardwareExposure(): Pair<Int?, Long?> {
        val effectiveIso = lastSensorIso?.let { iso ->
            (iso * (lastPostRawBoost.coerceIn(100, 1600) / 100.0)).roundToInt()
        }
        return effectiveIso to lastExposureTimeNs
    }

    /**
     * Returns the latest tracked faces in upright normalized [0, 1] coordinates.
     * When capturing a front-camera photo with [mirrorFrontCamera] disabled, flips X back so
     * face ROIs align 1:1 with the unmirrored captured bitmap.
     */
    fun getCaptureFaces(mirrorFrontCamera: Boolean): List<DetectedFace> {
        if (!activeFaceDetectEnabled) return emptyList()
        val current = smoothedFaces
        if (current.isEmpty()) return emptyList()
        val isFront = currentLensFacing == CameraSelector.LENS_FACING_FRONT
        if (!isFront || mirrorFrontCamera) return current
        return current.map { f ->
            f.copy(
                left = (1f - f.right).coerceIn(0f, 1f),
                right = (1f - f.left).coerceIn(0f, 1f),
            )
        }
    }

    fun setFaceDetectionEnabled(enabled: Boolean) {
        activeFaceDetectEnabled = enabled
        if (!enabled && smoothedFaces.isNotEmpty()) {
            smoothedFaces = emptyList()
            onFacesDetectedCallback?.invoke(emptyList())
        }
    }

    fun getCapabilities(): HardwareCapabilities = currentCapabilities

    @OptIn(ExperimentalCamera2Interop::class)
    suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lensFacing: Int,
        preferredCameraId: String? = null,
        aspectRatioMode: AspectRatioMode,
        flashMode: FlashMode,
        pixelCameraServicesEnabled: Boolean = false,
        requestedExtensionMode: PixelExtensionMode = PixelExtensionMode.NONE,
        nativeUltraHdrRequested: Boolean = false,
        faceDetectionEnabled: Boolean = true,
        isHistogramVisible: () -> Boolean = { true },
        isAnalysisPaused: () -> Boolean = { false },
        onLowLightBoostStateChanged: (Boolean) -> Unit = {},
        onFacesDetected: (List<DetectedFace>) -> Unit = {},
        onLiveAnalysis: (LiveFrameAnalysisResult) -> Unit,
    ): HardwareCapabilities {
        val provider = getCameraProvider()
        cameraProvider = provider
        currentLensFacing = lensFacing
        activeAspectRatioMode = aspectRatioMode
        activeFaceDetectEnabled = faceDetectionEnabled
        boundPreviewViewRef = WeakReference(previewView)
        activeDisplayRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        onFacesDetectedCallback = onFacesDetected
        smoothedFaces = emptyList()
        onFacesDetected(emptyList())

        val aspectRatio = when (aspectRatioMode) {
            AspectRatioMode.RATIO_4_3 -> AspectRatio.RATIO_4_3
            AspectRatioMode.RATIO_16_9 -> AspectRatio.RATIO_16_9
        }
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                AspectRatioStrategy(aspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO)
            )
            .build()

        val baseFacingSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        // Probe & cache CameraX Extensions availability off the main thread to avoid vendor IPC jank
        val (extMgr, supportedExtensions) = resolveExtensionsOffMainThread(
            provider = provider,
            lensFacing = lensFacing,
            baseFacingSelector = baseFacingSelector,
        )
        val anyExtensionAvailable = supportedExtensions.isNotEmpty()

        val resolvedExtensionModeInt: Int? = if (
            pixelCameraServicesEnabled &&
            extMgr != null &&
            requestedExtensionMode != PixelExtensionMode.NONE
        ) {
            val candidates = when (requestedExtensionMode) {
                PixelExtensionMode.NIGHT -> intArrayOf(ExtensionMode.NIGHT)
                PixelExtensionMode.HDR -> intArrayOf(ExtensionMode.HDR, ExtensionMode.AUTO)
                PixelExtensionMode.AUTO -> intArrayOf(ExtensionMode.AUTO, ExtensionMode.HDR)
            }
            candidates.firstOrNull { mode ->
                mapExtensionModeInt(mode) in supportedExtensions
            }
        } else {
            null
        }
        val activeExtensionMode = mapExtensionModeInt(resolvedExtensionModeInt)

        lastAfState = CaptureResult.CONTROL_AF_STATE_INACTIVE
        lastSensorIso = null
        lastPostRawBoost = 100
        lastExposureTimeNs = null
        lowLightBoostEnabled = false
        hasActiveFocusMetering = false
        hasActiveCamera2Overrides = false
        lastAppliedEvIndex = null

        // Target full native 12.5MP binned sensor resolution (4080x3072 / 4000x3000) instead of 7MP
        val targetCaptureSize = if (aspectRatioMode == AspectRatioMode.RATIO_4_3) {
            Size(4096, 3072)
        } else {
            Size(4096, 2304)
        }
        val captureResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                AspectRatioStrategy(aspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO)
            )
            .setResolutionStrategy(
                ResolutionStrategy(targetCaptureSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .build()

        val preview: Preview
        var imageCapture: ImageCapture
        val imageAnalysis: ImageAnalysis?
        var camera: Camera
        var nativeUltraHdrSupported = false
        var nativeUltraHdrActive = false

        if (resolvedExtensionModeInt != null && extMgr != null) {
            // Pixel Camera Services (CameraX Extensions) session:
            // Uses ExtensionSessionConfig (CameraX 1.6+/1.7) without ImageAnalysis or CaptureRequest overrides.
            // Attach read-only telemetry callback so hardware ISO/exposure are observed whenever exposed by HAL.
            val extPreviewBuilder = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
            runCatching {
                Camera2Interop.Extender(extPreviewBuilder)
                    .setSessionCaptureCallback(createPreviewTelemetryCallback())
            }
            preview = extPreviewBuilder
                .build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(captureResolutionSelector)
                .setJpegQuality(97)
                .setFlashMode(mapFlashMode(flashMode))
                .build()
            imageAnalysis = null

            val extConfig = ExtensionSessionConfig.Builder(resolvedExtensionModeInt, extMgr)
                .addUseCase(preview)
                .addUseCase(imageCapture)
                .build()

            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, baseFacingSelector, extConfig)
        } else {
            val selector = if (preferredCameraId != null) {
                CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .addCameraFilter { infos ->
                        val matched = infos.filter {
                            runCatching { Camera2CameraInfo.from(it).cameraId == preferredCameraId }.getOrDefault(false)
                        }
                        matched.ifEmpty { infos }
                    }
                    .build()
            } else {
                baseFacingSelector
            }

            val (probedFaceMode, probedOrientation, probedArray) = probeFaceDetectConfig(provider, selector)
            val appliedFaceMode = if (faceDetectionEnabled) {
                probedFaceMode
            } else {
                CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
            }
            activeFaceDetectMode = appliedFaceMode
            activeSensorOrientation = probedOrientation
            activeSensorArray = probedArray

            val previewBuilder = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
            val previewExtender = Camera2Interop.Extender(previewBuilder)
                .setSessionCaptureCallback(createPreviewTelemetryCallback())
            if (probedFaceMode != CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF) {
                previewExtender.setCaptureRequestOption(
                    CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                    appliedFaceMode,
                )
            }
            preview = previewBuilder
                .build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

            val oisAvailableOnTarget = checkOisSupportedOnSelector(provider, selector)
            nativeUltraHdrSupported = checkNativeUltraHdrSupportedOnSelector(provider, selector)
            val useNativeUltraHdr = nativeUltraHdrRequested && nativeUltraHdrSupported

            fun buildStandardImageCapture(enableUltraHdr: Boolean): ImageCapture {
                // Use MINIMIZE_LATENCY for the custom computational engine:
                // 1. Keeps full 12.5MP resolution and JPEG quality 97.
                // 2. Eliminates CameraX's 5-second per-frame 3A convergence timeout during manual
                //    Night Sight (CONTROL_AE_MODE_OFF) and prevents slow per-frame vendor HQ stalls
                //    when capturing multi-frame HDR+ / Night Sight bursts.
                val builder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setResolutionSelector(captureResolutionSelector)
                    .setJpegQuality(97)
                    .setFlashMode(mapFlashMode(flashMode))
                if (enableUltraHdr && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    builder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
                }
                if (oisAvailableOnTarget) {
                    Camera2Interop.Extender(builder).setCaptureRequestOption(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON,
                    )
                }
                return builder.build()
            }

            imageCapture = buildStandardImageCapture(enableUltraHdr = useNativeUltraHdr)

            val analysisResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(
                    AspectRatioStrategy(aspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO)
                )
                .setResolutionStrategy(
                    ResolutionStrategy(Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                )
                .build()

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(analysisResolutionSelector)
                .build().also {
                    it.setAnalyzer(
                        analysisExecutor,
                        LiveFrameAnalyzer(
                            isHistogramVisible = isHistogramVisible,
                            isPaused = isAnalysisPaused,
                            detectedFacesProvider = { if (activeFaceDetectEnabled) smoothedFaces else emptyList() },
                            isFrontCameraProvider = { currentLensFacing == CameraSelector.LENS_FACING_FRONT },
                            onAnalyzed = onLiveAnalysis,
                        )
                    )
                }

            provider.unbindAll()
            camera = if (useNativeUltraHdr) {
                try {
                    nativeUltraHdrActive = true
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        preview,
                        imageCapture,
                        imageAnalysis,
                    )
                } catch (e: IllegalArgumentException) {
                    Log.w("CameraController", "OUTPUT_FORMAT_JPEG_ULTRA_HDR bind fallback to JPEG", e)
                    nativeUltraHdrActive = false
                    imageCapture = buildStandardImageCapture(enableUltraHdr = false)
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        preview,
                        imageCapture,
                        imageAnalysis,
                    )
                }
            } else {
                provider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    imageCapture,
                    imageAnalysis,
                )
            }
        }

        boundCamera = camera
        previewUseCase = preview
        imageCaptureUseCase = imageCapture
        imageAnalysisUseCase = imageAnalysis

        currentCapabilities = inspectCapabilities(
            provider = provider,
            camera = camera,
            lensFacing = lensFacing,
            activeCameraId = if (activeExtensionMode != PixelExtensionMode.NONE) null else preferredCameraId,
            pixelExtensionsSupported = anyExtensionAvailable,
            supportedPixelExtensions = supportedExtensions,
            activeExtensionMode = activeExtensionMode,
            nativeUltraHdrSupported = nativeUltraHdrSupported,
            nativeUltraHdrActive = nativeUltraHdrActive,
            isImageAnalysisSupportedInSession = imageAnalysis != null,
        )
        if (currentCapabilities.lowLightBoostSupported && activeExtensionMode == PixelExtensionMode.NONE) {
            runCatching {
                camera.cameraInfo.lowLightBoostState.observe(lifecycleOwner) { state ->
                    onLowLightBoostStateChanged(state == LowLightBoostState.ACTIVE)
                }
            }
        } else {
            onLowLightBoostStateChanged(false)
        }
        return currentCapabilities
    }

    private suspend fun resolveExtensionsOffMainThread(
        provider: ProcessCameraProvider,
        lensFacing: Int,
        baseFacingSelector: CameraSelector,
    ): Pair<ExtensionsManager?, Set<PixelExtensionMode>> {
        val existingMgr = extensionsManager
        val cachedModes = extensionSupportCache[lensFacing]
        if (existingMgr != null && cachedModes != null) {
            return existingMgr to cachedModes
        }
        return withContext(Dispatchers.IO) {
            val mgr = existingMgr ?: runCatching {
                ExtensionsManager.getInstance(context, provider)
            }.onFailure {
                Log.w("CameraController", "ExtensionsManager unavailable", it)
            }.getOrNull()?.also { extensionsManager = it }

            if (mgr == null) {
                return@withContext null to emptySet()
            }

            val modes = extensionSupportCache[lensFacing] ?: buildSet {
                if (runCatching { mgr.isExtensionAvailable(baseFacingSelector, ExtensionMode.AUTO) }.getOrDefault(false)) {
                    add(PixelExtensionMode.AUTO)
                }
                if (runCatching { mgr.isExtensionAvailable(baseFacingSelector, ExtensionMode.HDR) }.getOrDefault(false)) {
                    add(PixelExtensionMode.HDR)
                }
                if (runCatching { mgr.isExtensionAvailable(baseFacingSelector, ExtensionMode.NIGHT) }.getOrDefault(false)) {
                    add(PixelExtensionMode.NIGHT)
                }
            }.also { extensionSupportCache[lensFacing] = it }

            mgr to modes
        }
    }

    private fun checkNativeUltraHdrSupportedOnSelector(
        provider: ProcessCameraProvider,
        selector: CameraSelector,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return runCatching {
            val info = selector.filter(provider.availableCameraInfos).firstOrNull() ?: return@runCatching false
            ImageCapture.getImageCaptureCapabilities(info)
                .supportedOutputFormats
                .contains(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
        }.getOrDefault(false)
    }

    private fun mapExtensionModeInt(mode: Int?): PixelExtensionMode = when (mode) {
        ExtensionMode.AUTO -> PixelExtensionMode.AUTO
        ExtensionMode.HDR -> PixelExtensionMode.HDR
        ExtensionMode.NIGHT -> PixelExtensionMode.NIGHT
        else -> PixelExtensionMode.NONE
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun checkOisSupportedOnSelector(
        provider: ProcessCameraProvider,
        selector: CameraSelector,
    ): Boolean = runCatching {
        val info = selector.filter(provider.availableCameraInfos).firstOrNull() ?: return@runCatching false
        val modes = Camera2CameraInfo.from(info)
            .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        modes?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
    }.getOrDefault(false)

    @OptIn(ExperimentalCamera2Interop::class)
    private fun probeFaceDetectConfig(
        provider: ProcessCameraProvider,
        selector: CameraSelector,
    ): Triple<Int, Int, Rect?> = runCatching {
        val info = selector.filter(provider.availableCameraInfos).firstOrNull()
            ?: return@runCatching Triple(CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF, 90, null)
        val c2Info = Camera2CameraInfo.from(info)
        val modes = c2Info.getCameraCharacteristic(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)
        val preferredMode = when {
            modes?.contains(CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE) == true ->
                CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE
            modes?.contains(CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL) == true ->
                CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL
            else -> CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
        }
        val orientation = c2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val activeArray = c2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        Triple(preferredMode, orientation, activeArray)
    }.getOrDefault(Triple(CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF, 90, null))

    private fun createPreviewTelemetryCallback(): CameraCaptureSession.CaptureCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                result.get(CaptureResult.CONTROL_AF_STATE)?.let { afState ->
                    lastAfState = afState
                }
                result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { iso ->
                    if (iso > 0) lastSensorIso = iso
                }
                result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)?.let { boost ->
                    if (boost >= 100) lastPostRawBoost = boost
                }
                result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { expNs ->
                    if (expNs > 0L) lastExposureTimeNs = expNs
                }

                if (!activeFaceDetectEnabled) return
                val now = System.currentTimeMillis()
                if (now - lastFaceEmitTimeMs < 65L) return
                lastFaceEmitTimeMs = now

                val hwFaces = result.get(CaptureResult.STATISTICS_FACES)
                val cropRect = result.get(CaptureResult.SCALER_CROP_REGION) ?: activeSensorArray
                if (hwFaces.isNullOrEmpty() || cropRect == null) {
                    if (smoothedFaces.isNotEmpty()) {
                        smoothedFaces = emptyList()
                        onFacesDetectedCallback?.invoke(emptyList())
                    }
                    return
                }

                val isFront = currentLensFacing == CameraSelector.LENS_FACING_FRONT
                val displayRotation = boundPreviewViewRef?.get()?.display?.rotation ?: activeDisplayRotation
                activeDisplayRotation = displayRotation
                val isLandscape = displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270
                val targetUprightAspect = when (activeAspectRatioMode) {
                    AspectRatioMode.RATIO_4_3 -> if (isLandscape) 4f / 3f else 3f / 4f
                    AspectRatioMode.RATIO_16_9 -> if (isLandscape) 16f / 9f else 9f / 16f
                }
                val relativeRotation = runCatching {
                    boundCamera?.cameraInfo?.getSensorRotationDegrees(displayRotation)
                }.getOrNull() ?: run {
                    val displayDeg = when (displayRotation) {
                        Surface.ROTATION_90 -> 90
                        Surface.ROTATION_180 -> 180
                        Surface.ROTATION_270 -> 270
                        else -> 0
                    }
                    if (isFront) {
                        (activeSensorOrientation + displayDeg) % 360
                    } else {
                        (activeSensorOrientation - displayDeg + 360) % 360
                    }
                }
                val rawZoomRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    result.get(CaptureResult.CONTROL_ZOOM_RATIO)
                } else null
                val activeArr = activeSensorArray
                val alreadyCroppedByScaler = activeArr != null &&
                    activeArr.width() > 0 &&
                    cropRect.width() < (activeArr.width() * 0.98f)
                val effectiveZoomRatio = if (!alreadyCroppedByScaler && rawZoomRatio != null && rawZoomRatio > 1.01f) {
                    rawZoomRatio
                } else 1f

                val mapped = ArrayList<DetectedFace>(hwFaces.size)
                for (i in hwFaces.indices) {
                    val face = hwFaces[i]
                    if (face.score < 42) continue
                    val bounds = face.bounds ?: continue
                    val normalized = FaceCoordinateMapper.mapSensorRectToNormalizedUpright(
                        faceLeft = bounds.left,
                        faceTop = bounds.top,
                        faceRight = bounds.right,
                        faceBottom = bounds.bottom,
                        cropLeft = cropRect.left,
                        cropTop = cropRect.top,
                        cropRight = cropRect.right,
                        cropBottom = cropRect.bottom,
                        sensorOrientationDegrees = relativeRotation,
                        mirrorHorizontally = isFront,
                        targetUprightAspect = targetUprightAspect,
                        zoomRatio = effectiveZoomRatio,
                        faceId = if (face.id >= 0) face.id else i,
                        score = face.score,
                    )
                    if (normalized != null) {
                        mapped.add(normalized)
                    }
                }
                val nextFaces = FaceCoordinateMapper.smoothFaces(smoothedFaces, mapped, alpha = 0.44f)
                if (nextFaces != smoothedFaces) {
                    smoothedFaces = nextFaces
                    onFacesDetectedCallback?.invoke(nextFaces)
                }
            }
        }

    /**
     * Zero-allocation live brightness sample from PreviewView's TextureView (24x18 = 432 px).
     * Must be called on the main thread; used while Auto Pixel Night Sight is active (when
     * ImageAnalysis is unavailable) to detect when the actual camera scene becomes bright again.
     */
    fun samplePreviewBrightness(previewView: PreviewView?): Pair<Float, Float>? {
        val pv = previewView ?: return null
        var tv: TextureView? = null
        for (i in 0 until pv.childCount) {
            val child = pv.getChildAt(i)
            if (child is TextureView) {
                tv = child
                break
            }
        }
        val textureView = tv ?: return null
        if (!textureView.isAvailable || textureView.width <= 0 || textureView.height <= 0) return null

        val bmp = previewProbeBitmap?.takeIf { !it.isRecycled }
            ?: Bitmap.createBitmap(PREVIEW_PROBE_W, PREVIEW_PROBE_H, Bitmap.Config.ARGB_8888)
                .also { previewProbeBitmap = it }
        val captured = runCatching { textureView.getBitmap(bmp) }.getOrNull() ?: return null
        captured.getPixels(previewProbePixels, 0, PREVIEW_PROBE_W, 0, 0, PREVIEW_PROBE_W, PREVIEW_PROBE_H)

        var sumLuma = 0.0
        var crushed = 0
        val count = previewProbePixels.size
        for (i in 0 until count) {
            val p = previewProbePixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val luma = 0.299 * r + 0.587 * g + 0.114 * b
            sumLuma += luma
            if (luma < 24.0) crushed++
        }
        val meanLuma = (sumLuma / (count * 255.0)).toFloat().coerceIn(0f, 1f)
        val crushedPct = (crushed * 100f / count).coerceIn(0f, 100f)
        return meanLuma to crushedPct
    }

    /**
     * Toggles CameraX 1.5+ AE-mode Low Light Boost for brighter real-time low-light viewfinder preview.
     */
    fun setLowLightBoostEnabled(enabled: Boolean) {
        val camera = boundCamera ?: return
        if (currentCapabilities.activeExtensionMode != PixelExtensionMode.NONE) return
        if (!currentCapabilities.lowLightBoostSupported) return
        if (lowLightBoostEnabled == enabled) return
        lowLightBoostEnabled = enabled
        val future = camera.cameraControl.enableLowLightBoostAsync(enabled)
        future.addListener(
            {
                runCatching { future.get() }.onFailure { err ->
                    lowLightBoostEnabled = false
                    val msg = err.cause?.message ?: err.message.orEmpty()
                    if (msg.contains("new enableLowLightBoost", ignoreCase = true) ||
                        msg.contains("canceled", ignoreCase = true)
                    ) {
                        Log.d("CameraController", "Low Light Boost superseded during rebind")
                    } else {
                        Log.w("CameraController", "Low Light Boost toggle failed", err)
                    }
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    @OptIn(ExperimentalCamera2Interop::class, ExperimentalZeroShutterLag::class)
    private fun inspectCapabilities(
        provider: ProcessCameraProvider,
        camera: Camera,
        lensFacing: Int,
        activeCameraId: String?,
        pixelExtensionsSupported: Boolean = false,
        supportedPixelExtensions: Set<PixelExtensionMode> = emptySet(),
        activeExtensionMode: PixelExtensionMode = PixelExtensionMode.NONE,
        nativeUltraHdrSupported: Boolean = false,
        nativeUltraHdrActive: Boolean = false,
        isImageAnalysisSupportedInSession: Boolean = true,
    ): HardwareCapabilities {
        val exposureState = camera.cameraInfo.exposureState
        val evRange = exposureState.exposureCompensationRange
        val evStep = exposureState.exposureCompensationStep.toFloat().let { if (it > 0f) it else 0.33f }
        val zslSupported = runCatching { camera.cameraInfo.isZslSupported }.getOrDefault(false)
        val lowLightBoostSupported = runCatching { camera.cameraInfo.isLowLightBoostSupported }.getOrDefault(false)

        val zoomState = camera.cameraInfo.zoomState.value
        val minZoom = (zoomState?.minZoomRatio ?: 1f).coerceAtLeast(0.4f)
        val maxZoom = (zoomState?.maxZoomRatio ?: 8f).coerceAtLeast(1f)

        val discoveredLenses = discoverLensOptions(
            provider = provider,
            activeCamera = camera,
            lensFacing = lensFacing,
            activeCameraId = activeCameraId,
            minZoom = minZoom,
            maxZoom = maxZoom,
        )

        val (focal35, aperture) = extractOpticalSpecs(camera.cameraInfo)

        return try {
            val c2Info = Camera2CameraInfo.from(camera.cameraInfo)
            val caps = c2Info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val rawSupported = caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
            val manualSupported = caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
            val isoRangeAndroid = c2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val isoRange = if (isoRangeAndroid != null) {
                isoRangeAndroid.lower.coerceAtLeast(25)..isoRangeAndroid.upper.coerceAtMost(12800)
            } else 50..6400
            val expRangeAndroid = c2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val exposureTimeRangeNs = if (expRangeAndroid != null) {
                expRangeAndroid.lower.coerceAtLeast(100_000L)..expRangeAndroid.upper.coerceAtMost(4_000_000_000L)
            } else 100_000L..1_000_000_000L
            val fpsRanges = c2Info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            val lowestAeFpsRange = fpsRanges
                ?.filter { it.lower in 1..20 }
                ?.minWithOrNull(compareBy<Range<Int>> { it.lower }.thenBy { it.upper })
            val postRawRange = c2Info.getCameraCharacteristic(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)
            val postRawBoostSupported = postRawRange != null && postRawRange.upper > 100
            val oisModes = c2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            val oisSupported = oisModes?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
            val nrModes = c2Info.getCameraCharacteristic(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            val minimalNoiseReductionSupported =
                nrModes?.contains(CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL) == true
            val minFocusDist = c2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val afModes = c2Info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            val hasAutoFocus = minFocusDist > 0f && (afModes?.any { it != CameraMetadata.CONTROL_AF_MODE_OFF } ?: true)
            val faceModes = c2Info.getCameraCharacteristic(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)
            val faceDetectMode = when {
                faceModes?.contains(CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE) == true ->
                    CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE
                faceModes?.contains(CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL) == true ->
                    CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL
                else -> CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
            }
            val sensorOrientation = c2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val sensorActiveArray = c2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            activeSensorOrientation = sensorOrientation
            activeSensorArray = sensorActiveArray
            if (activeExtensionMode == PixelExtensionMode.NONE) {
                activeFaceDetectMode = faceDetectMode
            }

            HardwareCapabilities(
                rawSupported = rawSupported,
                manualSensorSupported = manualSupported,
                zslSupported = zslSupported,
                autoFocusSupported = hasAutoFocus,
                lowLightBoostSupported = lowLightBoostSupported,
                postRawBoostSupported = postRawBoostSupported,
                oisSupported = oisSupported,
                minimalNoiseReductionSupported = minimalNoiseReductionSupported,
                pixelExtensionsSupported = pixelExtensionsSupported,
                supportedPixelExtensions = supportedPixelExtensions,
                activeExtensionMode = activeExtensionMode,
                nativeUltraHdrSupported = nativeUltraHdrSupported,
                nativeUltraHdrActive = nativeUltraHdrActive,
                isImageAnalysisSupportedInSession = isImageAnalysisSupportedInSession,
                minEvIndex = evRange.lower,
                maxEvIndex = evRange.upper,
                evStep = evStep,
                isoRange = isoRange,
                exposureTimeRangeNs = exposureTimeRangeNs,
                lowestAeFpsRange = lowestAeFpsRange,
                minFocusDiopter = minFocusDist.coerceAtLeast(1f),
                minZoomRatio = minZoom,
                maxZoomRatio = maxZoom,
                activeFocalLengthMm35 = focal35,
                activeAperture = aperture,
                faceDetectMode = faceDetectMode,
                sensorOrientation = sensorOrientation,
                sensorActiveArray = sensorActiveArray,
                lensOptions = discoveredLenses,
            )
        } catch (e: Exception) {
            Log.w("CameraController", "Camera2 info fallback", e)
            HardwareCapabilities(
                zslSupported = zslSupported,
                autoFocusSupported = true,
                lowLightBoostSupported = lowLightBoostSupported,
                pixelExtensionsSupported = pixelExtensionsSupported,
                supportedPixelExtensions = supportedPixelExtensions,
                activeExtensionMode = activeExtensionMode,
                nativeUltraHdrSupported = nativeUltraHdrSupported,
                nativeUltraHdrActive = nativeUltraHdrActive,
                isImageAnalysisSupportedInSession = isImageAnalysisSupportedInSession,
                minEvIndex = evRange.lower,
                maxEvIndex = evRange.upper,
                evStep = evStep,
                minZoomRatio = minZoom,
                maxZoomRatio = maxZoom,
                activeFocalLengthMm35 = focal35,
                activeAperture = aperture,
                lensOptions = discoveredLenses,
            )
        }
    }

    /**
     * Discovers both logical multi-camera zoom presets (.5x, 1x, 2x, 5x) and any discrete physical
     * cameras exposed separately on the device for the given [lensFacing].
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun discoverLensOptions(
        provider: ProcessCameraProvider,
        activeCamera: Camera,
        lensFacing: Int,
        activeCameraId: String?,
        minZoom: Float,
        maxZoom: Float,
    ): List<LensOption> {
        val (baseFocal, baseAperture) = extractOpticalSpecs(activeCamera.cameraInfo)

        // All cameras exposed for this facing (logical default + any discrete physical sensors)
        val sameFacingInfos = provider.availableCameraInfos.filter { info ->
            runCatching {
                val c2 = Camera2CameraInfo.from(info)
                c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING) == lensFacing
            }.getOrDefault(false)
        }
        val defaultMainInfo = sameFacingInfos.firstOrNull()
        val defaultMainId = defaultMainInfo?.let {
            runCatching { Camera2CameraInfo.from(it).cameraId }.getOrNull()
        }
        val (mainFocal, mainAperture) = defaultMainInfo?.let { extractOpticalSpecs(it) } ?: (baseFocal to baseAperture)

        val options = mutableListOf<LensOption>()

        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            if (minZoom <= 0.85f) {
                options.add(
                    LensOption(
                        id = "front_uw",
                        label = ".7",
                        zoomRatio = minZoom.coerceAtLeast(0.6f),
                        cameraId = null,
                        focalLengthMm35 = (mainFocal * 0.7f).roundToInt(),
                        aperture = mainAperture,
                    )
                )
            }
            options.add(
                LensOption(
                    id = "front_1x",
                    label = "1x",
                    zoomRatio = 1f,
                    cameraId = null,
                    focalLengthMm35 = mainFocal,
                    aperture = mainAperture,
                )
            )
            if (maxZoom >= 1.4f) {
                options.add(
                    LensOption(
                        id = "front_14x",
                        label = "1.4",
                        zoomRatio = 1.4f,
                        cameraId = null,
                        focalLengthMm35 = (mainFocal * 1.4f).roundToInt(),
                        aperture = mainAperture,
                    )
                )
            }
            return options
        }

        // Back camera: check logical ultra-wide zoom first (only valid when bound to logical main camera),
        // or discrete physical ultra-wide camera ID different from the default main camera.
        val isOnDefaultLogical = activeCameraId == null || activeCameraId == defaultMainId
        if (isOnDefaultLogical && minZoom <= 0.75f) {
            options.add(
                LensOption(
                    id = "uw_logical",
                    label = ".5",
                    zoomRatio = minZoom.coerceAtLeast(0.5f),
                    cameraId = null,
                    focalLengthMm35 = 13,
                    aperture = mainAperture,
                )
            )
        } else {
            val discreteUw = sameFacingInfos.firstOrNull { info ->
                val cid = runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull()
                val (f35, _) = extractOpticalSpecs(info)
                cid != null && cid != defaultMainId && f35 in 10..19
            }
            if (discreteUw != null) {
                val cid = Camera2CameraInfo.from(discreteUw).cameraId
                val (f35, ap) = extractOpticalSpecs(discreteUw)
                options.add(
                    LensOption(
                        id = "uw_$cid",
                        label = ".5",
                        zoomRatio = 1f,
                        cameraId = cid,
                        focalLengthMm35 = f35,
                        aperture = ap,
                    )
                )
            } else if (minZoom <= 0.75f) {
                options.add(
                    LensOption(
                        id = "uw_logical",
                        label = ".5",
                        zoomRatio = minZoom.coerceAtLeast(0.5f),
                        cameraId = null,
                        focalLengthMm35 = 13,
                        aperture = mainAperture,
                    )
                )
            }
        }

        // Main Wide 1x (null cameraId selects default logical back camera)
        options.add(
            LensOption(
                id = "main_1x",
                label = "1x",
                zoomRatio = 1f,
                cameraId = null,
                focalLengthMm35 = if (mainFocal in 20..35) mainFocal else 24,
                aperture = mainAperture,
            )
        )

        // 2x Optical / Sensor-Crop Zoom on main logical camera
        if (maxZoom >= 2f || !isOnDefaultLogical) {
            options.add(
                LensOption(
                    id = "crop_2x",
                    label = "2",
                    zoomRatio = 2f,
                    cameraId = null,
                    focalLengthMm35 = 48,
                    aperture = mainAperture,
                )
            )
        }

        // 5x Telephoto (logical zoom or discrete tele lens)
        val discreteTele = sameFacingInfos.firstOrNull { info ->
            val cid = runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull()
            val (f35, _) = extractOpticalSpecs(info)
            cid != null && cid != defaultMainId && f35 >= 60
        }
        if (discreteTele != null && (!isOnDefaultLogical || maxZoom < 5f)) {
            val cid = Camera2CameraInfo.from(discreteTele).cameraId
            val (f35, ap) = extractOpticalSpecs(discreteTele)
            options.add(
                LensOption(
                    id = "tele_$cid",
                    label = "5",
                    zoomRatio = 1f,
                    cameraId = cid,
                    focalLengthMm35 = f35,
                    aperture = ap,
                )
            )
        } else if (maxZoom >= 5f) {
            options.add(
                LensOption(
                    id = "tele_5x",
                    label = "5",
                    zoomRatio = 5f,
                    cameraId = null,
                    focalLengthMm35 = 120,
                    aperture = mainAperture,
                )
            )
        } else if (maxZoom >= 3.5f) {
            options.add(
                LensOption(
                    id = "tele_max",
                    label = "${maxZoom.toInt()}",
                    zoomRatio = maxZoom.toInt().toFloat(),
                    cameraId = null,
                    focalLengthMm35 = (24 * maxZoom.toInt()),
                    aperture = mainAperture,
                )
            )
        }

        return options
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun extractOpticalSpecs(cameraInfo: CameraInfo): Pair<Int, Float?> {
        return try {
            val c2 = Camera2CameraInfo.from(cameraInfo)
            val focals = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val sensorSize = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val apertures = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            val f = focals?.firstOrNull()
            val focal35 = if (f != null && sensorSize != null && sensorSize.width > 0f && sensorSize.height > 0f) {
                val diagMm = sqrt(sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height)
                // 35mm full-frame diagonal = 43.27mm
                ((f * 43.27f) / diagMm).roundToInt().coerceIn(10, 300)
            } else 24
            focal35 to apertures?.firstOrNull()
        } catch (_: Exception) {
            24 to null
        }
    }

    fun updateFlashMode(flashMode: FlashMode) {
        runCatching { imageCaptureUseCase?.flashMode = mapFlashMode(flashMode) }
    }

    fun setZoomRatio(ratio: Float): Float {
        val camera = boundCamera ?: return ratio
        val zoomState = camera.cameraInfo.zoomState.value
        val minZ = zoomState?.minZoomRatio ?: currentCapabilities.minZoomRatio
        val maxZ = zoomState?.maxZoomRatio ?: currentCapabilities.maxZoomRatio
        val clamped = ratio.coerceIn(minZ, maxZ)
        camera.cameraControl.setZoomRatio(clamped)
        return clamped
    }

    suspend fun triggerTapToFocus(
        previewView: PreviewView,
        x: Float,
        y: Float,
        manualFocusActive: Boolean,
        lockAeAf: Boolean = false,
    ): Boolean {
        val camera = boundCamera ?: return false
        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(x, y)
        val flags = if (manualFocusActive) {
            FocusMeteringAction.FLAG_AE
        } else {
            lastTapAfTimestampMs = System.currentTimeMillis()
            tapFocusInFlight = true
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        }

        // Flush any previous focus lock on the Camera2 HAL so re-tapping always triggers a fresh AF scan
        if (hasActiveFocusMetering) {
            withTimeoutOrNull(120L) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val cancelFuture = camera.cameraControl.cancelFocusAndMetering()
                    cancelFuture.addListener(
                        { if (cont.isActive) cont.resume(Unit) },
                        ContextCompat.getMainExecutor(context),
                    )
                }
            }
            hasActiveFocusMetering = false
        }

        lastAfState = CaptureResult.CONTROL_AF_STATE_INACTIVE
        val actionBuilder = FocusMeteringAction.Builder(point, flags)
        if (lockAeAf) {
            actionBuilder.disableAutoCancel()
        } else {
            actionBuilder.setAutoCancelDuration(3, TimeUnit.SECONDS)
        }
        val action = actionBuilder.build()

        return try {
            hasActiveFocusMetering = true
            val ok = awaitFocusFast(camera, action, timeoutMs = 1500L)
            if (ok) {
                lastTapFocusConvergedMs = System.currentTimeMillis()
            }
            ok
        } finally {
            tapFocusInFlight = false
        }
    }

    fun cancelFocusLock() {
        hasActiveFocusMetering = false
        lastTapFocusConvergedMs = 0L
        lastTapAfTimestampMs = 0L
        runCatching { boundCamera?.cameraControl?.cancelFocusAndMetering() }
    }

    /**
     * Ensures the lens is focused before triggering burst capture without adding artificial stalls.
     * Takes 0ms when already locked/focused or after a recent tap-to-focus.
     */
    suspend fun ensureFocusBeforeCapture(
        previewView: PreviewView?,
        manualFocusActive: Boolean,
        isAeAfLocked: Boolean = false,
    ) {
        val camera = boundCamera ?: return
        if (manualFocusActive || isAeAfLocked || !currentCapabilities.autoFocusSupported) return

        val af = lastAfState
        if (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
            af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
        ) {
            return
        }

        val now = System.currentTimeMillis()
        // Respect recent tap-to-focus once it has finished locking (0ms extra wait, never override tap point)
        if (!tapFocusInFlight && (now - lastTapFocusConvergedMs < 4000L || now - lastTapAfTimestampMs < 3000L)) {
            return
        }

        // If AF is actively scanning right now, wait briefly in tight 10ms intervals for lock
        if (tapFocusInFlight ||
            af == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN ||
            af == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN
        ) {
            val deadline = System.currentTimeMillis() + 240L
            while (System.currentTimeMillis() < deadline) {
                val cur = lastAfState
                if (!tapFocusInFlight ||
                    cur == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    cur == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
                ) {
                    return
                }
                delay(10L)
            }
            return
        }

        // Only trigger fast AF fallback if sensor explicitly reports unfocused state;
        // prioritize the primary detected face center over frame center when a face is tracked.
        val isExplicitlyUnfocused = af == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED ||
            af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
        if (isExplicitlyUnfocused && previewView != null && previewView.width > 0 && previewView.height > 0) {
            val primaryFace = if (activeFaceDetectEnabled) smoothedFaces.firstOrNull() else null
            val targetNormX = primaryFace?.centerX?.coerceIn(0.10f, 0.90f) ?: 0.5f
            val targetNormY = primaryFace?.centerY?.coerceIn(0.10f, 0.90f) ?: 0.5f
            val targetPoint = previewView.meteringPointFactory.createPoint(
                previewView.width * targetNormX,
                previewView.height * targetNormY,
            )
            val action = FocusMeteringAction.Builder(
                targetPoint,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            )
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
            hasActiveFocusMetering = true
            awaitFocusFast(camera, action, timeoutMs = 450L)
        }
    }

    private suspend fun awaitFocusFast(
        camera: Camera,
        action: FocusMeteringAction,
        timeoutMs: Long,
    ): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val future = camera.cameraControl.startFocusAndMetering(action)
                future.addListener(
                    {
                        if (cont.isActive) {
                            val ok = runCatching { future.get().isFocusSuccessful }.getOrDefault(false)
                            cont.resume(ok)
                        }
                    },
                    ContextCompat.getMainExecutor(context),
                )
            }
        } ?: (lastAfState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
            lastAfState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun applyManualSettings(settings: ManualExposureSettings) {
        val camera = boundCamera ?: return
        val caps = currentCapabilities

        if (!settings.isManualExposureActive || caps.activeExtensionMode != PixelExtensionMode.NONE) {
            val evIndex = (settings.exposureCompensationEv / caps.evStep)
                .roundToInt()
                .coerceIn(caps.minEvIndex, caps.maxEvIndex)
            if (evIndex != lastAppliedEvIndex) {
                lastAppliedEvIndex = evIndex
                runCatching { camera.cameraControl.setExposureCompensationIndex(evIndex) }
            }
        }

        // CameraX Extensions (Pixel Camera Services) owns AE/AWB/AF/ISP internally — never inject Camera2Interop overrides.
        if (caps.activeExtensionMode != PixelExtensionMode.NONE) {
            return
        }

        val needsManualAe = settings.isManualExposureActive && caps.manualSensorSupported
        val needsManualAf = settings.focusDistanceDiopter != null
        val needsManualAwb = settings.whiteBalanceKelvin != null
        val needsAnyCamera2Override = needsManualAe || needsManualAf || needsManualAwb

        // Never override CONTROL_AF_MODE / CONTROL_AE_MODE / CONTROL_AWB_MODE via Camera2Interop
        // when in Auto mode, as sticky Camera2 options take priority over CameraX's FocusMeteringControl
        // and prevent tap-to-focus & continuous AF from refocusing properly.
        if (!needsAnyCamera2Override) {
            if (hasActiveCamera2Overrides) {
                runCatching {
                    Camera2CameraControl.from(camera.cameraControl).clearCaptureRequestOptions()
                    hasActiveCamera2Overrides = false
                }.onFailure {
                    Log.w("CameraController", "Failed to clear Camera2 overrides", it)
                }
            }
            return
        }

        runCatching {
            val c2Control = Camera2CameraControl.from(camera.cameraControl)
            val builder = CaptureRequestOptions.Builder()

            if (needsManualAe) {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CameraMetadata.CONTROL_AE_MODE_OFF,
                )
                settings.iso?.let { iso ->
                    builder.setCaptureRequestOption(
                        CaptureRequest.SENSOR_SENSITIVITY,
                        iso.coerceIn(caps.isoRange.first, caps.isoRange.last),
                    )
                }
                settings.shutterSpeedNs?.let { ns ->
                    builder.setCaptureRequestOption(
                        CaptureRequest.SENSOR_EXPOSURE_TIME,
                        ns,
                    )
                }
            }

            if (needsManualAf) {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_OFF,
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    settings.focusDistanceDiopter!!.coerceIn(0f, caps.minFocusDiopter),
                )
            }

            if (needsManualAwb) {
                val (r, g, b) = KelvinColorMath.kelvinToRgbGains(settings.whiteBalanceKelvin!!)
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CameraMetadata.CONTROL_AWB_MODE_OFF,
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_GAINS,
                    RggbChannelVector(r, g, g, b),
                )
            }

            c2Control.setCaptureRequestOptions(builder.build())
            hasActiveCamera2Overrides = true
        }.onFailure {
            Log.w("CameraController", "Camera2 manual override unsupported on this sensor", it)
        }
    }

    /**
     * Single-shot native capture for Pixel Camera Services (CameraX Extensions) sessions:
     * delegates multi-frame RAW fusion, backlit HDR+, and Night Sight entirely to Google's vendor pipeline.
     */
    suspend fun captureExtensionSinglePhoto(
        mirrorFrontCamera: Boolean,
        previewView: PreviewView? = null,
        isAeAfLocked: Boolean = false,
        onCaptureProgress: (percent: Int) -> Unit = {},
    ): List<RawCapturedFrame> = captureMutex.withLock {
        withContext(Dispatchers.Main) {
            val imageCapture = imageCaptureUseCase
                ?: throw IllegalStateException("Camera is not bound")

            ensureFocusBeforeCapture(
                previewView = previewView,
                manualFocusActive = false,
                isAeAfLocked = isAeAfLocked,
            )

            val isFront = currentLensFacing == CameraSelector.LENS_FACING_FRONT
            val mirror = isFront && mirrorFrontCamera
            try {
                val imageProxy = takeSingleImageProxy(imageCapture, onCaptureProgress)
                val frame = withContext(Dispatchers.Default) {
                    extractRawFrameAndClose(imageProxy, mirror, maxLongEdge = MAX_CAPTURE_LONG_EDGE)
                }
                listOf(frame)
            } finally {
                if (!isAeAfLocked && hasActiveFocusMetering) {
                    cancelFocusLock()
                }
            }
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    suspend fun captureHdrBurst(
        hdrMode: HdrMode,
        requestedFrameCount: Int,
        manualSettings: ManualExposureSettings,
        mirrorFrontCamera: Boolean,
        isBacklit: Boolean = false,
        previewView: PreviewView? = null,
        isAeAfLocked: Boolean = false,
        onFrameCaptured: (captured: Int, total: Int) -> Unit,
    ): List<RawCapturedFrame> = captureMutex.withLock {
        withContext(Dispatchers.Main) {
            val camera = boundCamera ?: throw IllegalStateException("Camera is not bound")
            val imageCapture = imageCaptureUseCase
                ?: throw IllegalStateException("Camera is not bound")
            val caps = currentCapabilities

            ensureFocusBeforeCapture(
                previewView = previewView,
                manualFocusActive = manualSettings.focusDistanceDiopter != null,
                isAeAfLocked = isAeAfLocked,
            )

            val totalFrames = when (hdrMode) {
                HdrMode.OFF -> 1
                HdrMode.HDR_PLUS -> 2
                HdrMode.HDR_PLUS_ENHANCED -> requestedFrameCount.coerceIn(2, 5)
            }

            // Use real exposure bracketing in strongly backlit scenes so shadowed subjects receive
            // real sensor photons (+1.5 EV) instead of stretching noisy 8-bit shadows!
            val canBracket = isBacklit &&
                totalFrames >= 2 &&
                hdrMode != HdrMode.OFF &&
                !manualSettings.isManualExposureActive &&
                (caps.maxEvIndex - caps.minEvIndex) >= 4
            val evOffsets: FloatArray = if (canBracket) {
                FloatArray(totalFrames) { idx ->
                    when (idx) {
                        0 -> 0f
                        1 -> 1.5f
                        2 -> -1.0f
                        else -> 0f
                    }
                }
            } else {
                FloatArray(totalFrames) { 0f }
            }
            val baseEvIndex = (manualSettings.exposureCompensationEv / caps.evStep)
                .roundToInt()
                .coerceIn(caps.minEvIndex, caps.maxEvIndex)

            // Disable heavy single-frame ISP smoothing during multi-frame bursts to prevent double-denoising ("soap")
            val appliedBurstNrOverride = if (totalFrames > 1 && !hasActiveCamera2Overrides) {
                applyMultiFrameBurstQualityOptions(camera, caps)
            } else {
                false
            }

            val isFront = currentLensFacing == CameraSelector.LENS_FACING_FRONT
            val mirror = isFront && mirrorFrontCamera
            val rawFrames = ArrayList<RawCapturedFrame>(totalFrames)

            try {
                for (idx in 0 until totalFrames) {
                    val evDelta = evOffsets[idx]
                    if (canBracket) {
                        val targetIdx = ((manualSettings.exposureCompensationEv + evDelta) / caps.evStep)
                            .roundToInt()
                            .coerceIn(caps.minEvIndex, caps.maxEvIndex)
                        if (targetIdx != lastAppliedEvIndex) {
                            lastAppliedEvIndex = targetIdx
                            runCatching { camera.cameraControl.setExposureCompensationIndex(targetIdx) }
                            delay(150L)
                        }
                    }
                    val imageProxy = takeSingleImageProxy(imageCapture)
                    // Extract compact JPEG bytes and close ImageProxy IMMEDIATELY before requesting
                    // the next frame so CameraX's internal ImageReader buffer never exhausts or stalls.
                    val frame = withContext(Dispatchers.Default) {
                        extractRawFrameAndClose(
                            image = imageProxy,
                            mirrorHorizontally = mirror,
                            maxLongEdge = MAX_CAPTURE_LONG_EDGE,
                            exposureBiasEv = evDelta,
                        )
                    }
                    rawFrames.add(frame)
                    onFrameCaptured(idx + 1, totalFrames)
                }
                rawFrames
            } catch (t: Throwable) {
                rawFrames.forEach { it.release() }
                throw t
            } finally {
                if (canBracket && lastAppliedEvIndex != baseEvIndex) {
                    lastAppliedEvIndex = baseEvIndex
                    runCatching { camera.cameraControl.setExposureCompensationIndex(baseEvIndex) }
                }
                if (appliedBurstNrOverride) {
                    runCatching {
                        Camera2CameraControl.from(camera.cameraControl).clearCaptureRequestOptions()
                        hasActiveCamera2Overrides = false
                    }
                    applyManualSettings(manualSettings)
                }
                // Immediately release any temporary tap-focus lock after capture so continuous AF resumes
                if (!isAeAfLocked && hasActiveFocusMetering) {
                    cancelFocusLock()
                }
            }
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyMultiFrameBurstQualityOptions(
        camera: Camera,
        caps: HardwareCapabilities,
    ): Boolean = runCatching {
        val nrMode = if (caps.minimalNoiseReductionSupported) {
            CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
        } else {
            CameraMetadata.NOISE_REDUCTION_MODE_FAST
        }
        val builder = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, nrMode)
            .setCaptureRequestOption(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
        if (caps.oisSupported) {
            builder.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON,
            )
        }
        Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(builder.build())
        hasActiveCamera2Overrides = true
        true
    }.getOrDefault(false)

    /**
     * Pixel Night Sight style long-exposure capture:
     * 1. Extends hardware per-frame shutter exposure time (160ms–450ms) with controlled ISO on
     *    MANUAL_SENSOR devices, or lowers AE FPS range + lifts EV on limited HALs so the shutter
     *    stays physically open longer per frame.
     * 2. Captures 5–8 freshly exposed frames sequentially while reporting continuous progress for
     *    the "Hold still" shutter ring animation.
     * 3. Always restores normal camera AE/manual settings immediately upon completion or cancellation.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    suspend fun captureLowLightBurst(
        lowLightSeverity: Float,
        meanLuma: Float,
        clippedHighlightPercent: Float = 0f,
        manualSettings: ManualExposureSettings,
        mirrorFrontCamera: Boolean,
        previewView: PreviewView? = null,
        isAeAfLocked: Boolean = false,
        onHoldProgress: (progress: Float, framesCaptured: Int, totalFrames: Int, remainingSec: Float) -> Unit,
    ): LowLightCaptureResult = captureMutex.withLock {
        withContext(Dispatchers.Main) {
            val camera = boundCamera ?: throw IllegalStateException("Camera is not bound")
            val imageCapture = imageCaptureUseCase
                ?: throw IllegalStateException("Camera is not bound")
            val caps = currentCapabilities

            ensureFocusBeforeCapture(
                previewView = previewView,
                manualFocusActive = manualSettings.focusDistanceDiopter != null,
                isAeAfLocked = isAeAfLocked,
            )

            val (effectiveHwIso, effectiveHwExpNs) = getLatestHardwareExposure()
            var activePlan = LowLightDetector.planLongExposure(
                severity = lowLightSeverity,
                meanLuma = meanLuma,
                currentIso = effectiveHwIso,
                currentExposureNs = effectiveHwExpNs,
                isoRange = caps.isoRange,
                exposureTimeRangeNs = caps.exposureTimeRangeNs,
                clippedHighlightPercent = clippedHighlightPercent,
            )

            val previousLlbEnabled = lowLightBoostEnabled
            if (previousLlbEnabled) {
                setLowLightBoostEnabled(false)
            }

            val useManualLongShutter = caps.manualSensorSupported && !manualSettings.isManualExposureActive
            var appliedOverride = applyNightSightHardwareRequest(
                camera = camera,
                caps = caps,
                manualSettings = manualSettings,
                plan = activePlan,
                useManualLongShutter = useManualLongShutter,
            )

            val isFront = currentLensFacing == CameraSelector.LENS_FACING_FRONT
            val mirror = isFront && mirrorFrontCamera
            val rawFrames = ArrayList<RawCapturedFrame>(activePlan.frameCount)
            val holdStartMs = System.currentTimeMillis()
            var capturedCount = 0
            var lastFrameTimestampNs = -1L

            suspend fun waitForExposureSettle(targetExposureNs: Long) {
                if (!appliedOverride) return
                val targetExpMs = (targetExposureNs / 1_000_000L).coerceIn(50L, 280L)
                if (useManualLongShutter) {
                    val settleDeadline = System.currentTimeMillis() + (targetExpMs + 90L).coerceAtMost(320L)
                    while (System.currentTimeMillis() < settleDeadline) {
                        val liveExp = lastExposureTimeNs
                        if (liveExp != null && kotlin.math.abs(liveExp - targetExposureNs) <= (targetExposureNs * 0.30f).toLong()) {
                            break
                        }
                        delay(20L)
                    }
                } else {
                    delay(160L)
                }
            }

            try {
                waitForExposureSettle(activePlan.perFrameExposureNs)

                val targetHoldMs = activePlan.estimatedTotalHoldMs.coerceAtLeast(800L)
                // Hard real-time ceiling so even a slow HAL never traps the user for 10-60s
                val maxHoldBudgetMs = (targetHoldMs + 1400L).coerceIn(2800L, 4200L)
                var minInterFrameMs = (activePlan.perFrameExposureNs / 1_000_000L).coerceIn(60L, 240L)

                coroutineScope {
                    val tickerJob = launch {
                        while (isActive) {
                            val elapsed = System.currentTimeMillis() - holdStartMs
                            val timeFrac = (elapsed.toFloat() / targetHoldMs.toFloat()).coerceIn(0.03f, 0.96f)
                            val frameFrac = capturedCount.toFloat() / activePlan.frameCount.toFloat()
                            val blended = maxOf(timeFrac, frameFrac).coerceIn(0.03f, 0.98f)
                            val remSec = ((targetHoldMs - elapsed).coerceAtLeast(0L) / 1000f)
                            onHoldProgress(blended, capturedCount, activePlan.frameCount, remSec)
                            delay(50L)
                        }
                    }

                    try {
                        var didClosedLoopCorrection = false
                        while (rawFrames.size < activePlan.frameCount) {
                            val frameStartMs = System.currentTimeMillis()
                            var imageProxy = takeSingleImageProxy(imageCapture)
                            val ts = imageProxy.imageInfo.timestamp
                            if (lastFrameTimestampNs > 0L && ts == lastFrameTimestampNs) {
                                imageProxy.close()
                                delay(minInterFrameMs)
                                imageProxy = takeSingleImageProxy(imageCapture)
                            }
                            lastFrameTimestampNs = imageProxy.imageInfo.timestamp

                            val frame = withContext(Dispatchers.Default) {
                                extractRawFrameAndClose(imageProxy, mirror, maxLongEdge = MAX_CAPTURE_LONG_EDGE)
                            }
                            val frameElapsed = System.currentTimeMillis() - frameStartMs

                            // Closed-loop auto-metering on Frame 1 (only when the HAL is fast enough
                            // that discarding 1 probe frame stays well within our handheld time budget).
                            if (!didClosedLoopCorrection && rawFrames.isEmpty() && appliedOverride && frameElapsed < 1400L) {
                                didClosedLoopCorrection = true
                                val (frameLuma, frameClippedPct) = withContext(Dispatchers.Default) {
                                    measureQuickFrameBrightness(frame)
                                }
                                val isOverexposed = frameLuma > 0.54f || frameClippedPct > 14f
                                val isSeverelyUnderexposed = frameLuma < 0.09f && frameClippedPct < 1f
                                if (isOverexposed || isSeverelyUnderexposed) {
                                    val linearScale = if (isOverexposed) {
                                        val ratio = (0.34f / frameLuma.coerceAtLeast(0.36f)).coerceIn(0.20f, 0.86f)
                                        val clipPenalty = if (frameClippedPct > 14f) {
                                            (1f - ((frameClippedPct - 14f) / 38f)).coerceIn(0.45f, 0.88f)
                                        } else 1f
                                        (ratio * ratio * clipPenalty).coerceIn(0.14f, 0.80f)
                                    } else {
                                        val ratio = (0.28f / frameLuma.coerceAtLeast(0.05f)).coerceIn(1.1f, 1.55f)
                                        (ratio * ratio).coerceIn(1.2f, 2.0f)
                                    }
                                    activePlan = scalePlanExposure(
                                        plan = activePlan,
                                        linearScale = linearScale,
                                        isoRange = caps.isoRange,
                                        exposureTimeRangeNs = caps.exposureTimeRangeNs,
                                    )
                                    minInterFrameMs = (activePlan.perFrameExposureNs / 1_000_000L).coerceIn(60L, 240L)
                                    appliedOverride = applyNightSightHardwareRequest(
                                        camera = camera,
                                        caps = caps,
                                        manualSettings = manualSettings,
                                        plan = activePlan,
                                        useManualLongShutter = useManualLongShutter,
                                    )
                                    waitForExposureSettle(activePlan.perFrameExposureNs)
                                    // Discard the mis-exposed probe frame so it doesn't contaminate the merge
                                    frame.release()
                                    continue
                                }
                            }

                            rawFrames.add(frame)
                            capturedCount = rawFrames.size

                            // Adaptive wall-clock hold cap: if this device's HAL takes longer than expected
                            // per capture, finish cleanly once we have enough frames instead of hanging.
                            val totalElapsedMs = System.currentTimeMillis() - holdStartMs
                            val minFrames = if (frameElapsed >= 2500L) 1 else 2
                            if (rawFrames.size >= minFrames &&
                                (totalElapsedMs >= maxHoldBudgetMs || totalElapsedMs + frameElapsed > maxHoldBudgetMs + 900L)
                            ) {
                                break
                            }
                        }
                    } finally {
                        tickerJob.cancel()
                    }
                }

                val totalHoldMs = (System.currentTimeMillis() - holdStartMs).coerceAtLeast(1L)
                onHoldProgress(1.0f, rawFrames.size, activePlan.frameCount, 0f)
                LowLightCaptureResult(
                    frames = rawFrames,
                    perFrameExposureNs = if (useManualLongShutter) {
                        activePlan.perFrameExposureNs
                    } else {
                        lastExposureTimeNs ?: activePlan.perFrameExposureNs
                    },
                    isoUsed = if (useManualLongShutter) {
                        activePlan.targetIso
                    } else {
                        lastSensorIso ?: activePlan.targetIso
                    },
                    totalHoldMs = totalHoldMs,
                )
            } catch (t: Throwable) {
                rawFrames.forEach { it.release() }
                throw t
            } finally {
                restoreAfterNightSightCapture(
                    camera = camera,
                    manualSettings = manualSettings,
                    isAeAfLocked = isAeAfLocked,
                    restoreLlb = previousLlbEnabled,
                )
            }
        }
    }

    private fun scalePlanExposure(
        plan: com.example.camera.model.LowLightExposurePlan,
        linearScale: Float,
        isoRange: IntRange,
        exposureTimeRangeNs: LongRange,
    ): com.example.camera.model.LowLightExposurePlan {
        val minIso = isoRange.first.coerceAtLeast(25)
        val maxIso = isoRange.last.coerceAtMost(3200).coerceAtLeast(minIso)
        val currentProduct = plan.targetIso.toDouble() * plan.perFrameExposureNs.toDouble()
        val newProduct = (currentProduct * linearScale.toDouble()).coerceAtLeast(
            minIso.toDouble() * exposureTimeRangeNs.first.toDouble()
        )
        // Prefer reducing ISO first (keeping shutter long for low noise); shorten shutter if ISO hits floor
        val newExpNs = if (linearScale < 1f) {
            val maxSafeExp = (newProduct / minIso.toDouble()).toLong()
            minOf(plan.perFrameExposureNs, maxSafeExp)
                .coerceIn(exposureTimeRangeNs.first, exposureTimeRangeNs.last)
        } else {
            (plan.perFrameExposureNs * sqrt(linearScale.toDouble())).toLong()
                .coerceIn(exposureTimeRangeNs.first, exposureTimeRangeNs.last.coerceAtMost(280_000_000L))
        }
        val newIso = (newProduct / newExpNs.toDouble()).roundToInt().coerceIn(minIso, maxIso)
        val deltaEv = (kotlin.math.ln(linearScale.toDouble()) / kotlin.math.ln(2.0)).toFloat()
        return plan.copy(
            perFrameExposureNs = newExpNs,
            targetIso = newIso,
            evBoostStops = (plan.evBoostStops + deltaEv).coerceIn(-1.5f, 1.5f),
        )
    }

    private fun measureQuickFrameBrightness(frame: RawCapturedFrame): Pair<Float, Float> {
        val ownsBmp = frame !is RawCapturedFrame.DecodedBitmap
        val bmp = decodeRawFrame(frame, maxLongEdge = 160)
        return try {
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) return 0.34f to 0f
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            var sumLuma = 0.0
            var clipped = 0
            val step = (px.size / 1024).coerceAtLeast(1)
            var count = 0
            var i = 0
            while (i < px.size) {
                val p = px[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val luma = 0.299 * r + 0.587 * g + 0.114 * b
                sumLuma += luma
                if (luma >= 246.0) clipped++
                count++
                i += step
            }
            if (count == 0) 0.34f to 0f
            else ((sumLuma / (count * 255.0)).toFloat().coerceIn(0f, 1f)) to
                ((clipped * 100f / count).coerceIn(0f, 100f))
        } finally {
            if (ownsBmp && !bmp.isRecycled) bmp.recycle()
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private suspend fun applyNightSightHardwareRequest(
        camera: Camera,
        caps: HardwareCapabilities,
        manualSettings: ManualExposureSettings,
        plan: com.example.camera.model.LowLightExposurePlan,
        useManualLongShutter: Boolean,
    ): Boolean {
        return runCatching {
            val c2Control = Camera2CameraControl.from(camera.cameraControl)
            val builder = CaptureRequestOptions.Builder()

            if (useManualLongShutter) {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CameraMetadata.CONTROL_AE_MODE_OFF,
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                    plan.perFrameExposureNs,
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.SENSOR_FRAME_DURATION,
                    plan.perFrameExposureNs,
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.SENSOR_SENSITIVITY,
                    plan.targetIso.coerceIn(caps.isoRange.first, caps.isoRange.last),
                )
                if (caps.postRawBoostSupported) {
                    builder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST,
                        100,
                    )
                }
            } else {
                caps.lowestAeFpsRange?.let { fpsRange ->
                    builder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        fpsRange,
                    )
                }
                val boostedEv = (manualSettings.exposureCompensationEv + plan.evBoostStops.coerceIn(-1.5f, 1.2f))
                val evIndex = (boostedEv / caps.evStep)
                    .roundToInt()
                    .coerceIn(caps.minEvIndex, caps.maxEvIndex)
                lastAppliedEvIndex = evIndex
                runCatching { camera.cameraControl.setExposureCompensationIndex(evIndex) }
            }

            // Use MINIMAL/FAST ISP noise reduction when stacking 5-8 frames so the ISP does not
            // smear fine textures into "watercolor" before HdrPlusEngine's temporal denoise runs.
            val nrMode = if (caps.minimalNoiseReductionSupported) {
                CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
            } else {
                CameraMetadata.NOISE_REDUCTION_MODE_FAST
            }
            builder.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, nrMode)
            builder.setCaptureRequestOption(
                CaptureRequest.EDGE_MODE,
                CameraMetadata.EDGE_MODE_FAST,
            )
            if (caps.oisSupported) {
                builder.setCaptureRequestOption(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON,
                )
            }

            manualSettings.focusDistanceDiopter?.let { diopter ->
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_OFF,
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    diopter.coerceIn(0f, caps.minFocusDiopter),
                )
            }

            if (manualSettings.whiteBalanceKelvin != null) {
                val kelvin = manualSettings.whiteBalanceKelvin
                val (r, g, b) = KelvinColorMath.kelvinToRgbGains(kelvin)
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CameraMetadata.CONTROL_AWB_MODE_OFF,
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_GAINS,
                    RggbChannelVector(r, g, g, b),
                )
            } else {
                // Lock AWB across Night Sight burst frames so white balance stays consistent
                // and Camera2 never stalls waiting for AWB re-convergence between frames.
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
            }

            val future = c2Control.setCaptureRequestOptions(builder.build())
            hasActiveCamera2Overrides = true
            withTimeoutOrNull(350L) {
                suspendCancellableCoroutine<Unit> { cont ->
                    future.addListener(
                        { if (cont.isActive) cont.resume(Unit) },
                        ContextCompat.getMainExecutor(context),
                    )
                }
            }
            true
        }.getOrElse { err ->
            Log.w("CameraController", "Night Sight Camera2 long-exposure override fallback", err)
            false
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun restoreAfterNightSightCapture(
        camera: Camera,
        manualSettings: ManualExposureSettings,
        isAeAfLocked: Boolean,
        restoreLlb: Boolean,
    ) {
        runCatching {
            Camera2CameraControl.from(camera.cameraControl).clearCaptureRequestOptions()
            hasActiveCamera2Overrides = false
        }.onFailure {
            Log.w("CameraController", "Failed clearing Night Sight Camera2 options", it)
        }
        lastAppliedEvIndex = null
        applyManualSettings(manualSettings)
        if (!isAeAfLocked && hasActiveFocusMetering) {
            cancelFocusLock()
        }
        if (restoreLlb) {
            setLowLightBoostEnabled(true)
        }
    }

    private suspend fun takeSingleImageProxy(
        imageCapture: ImageCapture,
        onCaptureProgress: ((Int) -> Unit)? = null,
    ): ImageProxy = suspendCancellableCoroutine { cont ->
        imageCapture.takePicture(
            captureExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureProcessProgressed(progress: Int) {
                    onCaptureProgress?.invoke(progress)
                }

                override fun onCaptureSuccess(image: ImageProxy) {
                    if (cont.isActive) {
                        cont.resume(image)
                    } else {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    if (cont.isActive) cont.resumeWithException(exception)
                }
            }
        )
    }

    private fun extractRawFrameAndClose(
        image: ImageProxy,
        mirrorHorizontally: Boolean,
        maxLongEdge: Int,
        exposureBiasEv: Float = 0f,
    ): RawCapturedFrame {
        return try {
            val rotation = image.imageInfo.rotationDegrees
            val isJpegR = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                image.format == ImageFormat.JPEG_R
            if ((image.format == ImageFormat.JPEG || isJpegR) && image.planes.isNotEmpty()) {
                val buf = image.planes[0].buffer
                buf.rewind()
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                RawCapturedFrame.Jpeg(
                    bytes = bytes,
                    rotationDegrees = rotation,
                    mirrorHorizontally = mirrorHorizontally,
                    exposureBiasEv = exposureBiasEv,
                    isNativeUltraHdr = isJpegR,
                )
            } else {
                val rawBitmap = image.toBitmap()
                val oriented = orientAndDownscaleBitmap(rawBitmap, rotation, mirrorHorizontally, maxLongEdge)
                RawCapturedFrame.DecodedBitmap(oriented, exposureBiasEv = exposureBiasEv)
            }
        } finally {
            image.close()
        }
    }

    companion object {
        const val MAX_CAPTURE_LONG_EDGE = 4096
        private const val PREVIEW_PROBE_W = 24
        private const val PREVIEW_PROBE_H = 18

        fun decodeRawFrame(
            frame: RawCapturedFrame,
            maxLongEdge: Int = MAX_CAPTURE_LONG_EDGE,
        ): Bitmap = when (frame) {
            is RawCapturedFrame.DecodedBitmap -> frame.bitmap
            is RawCapturedFrame.Jpeg -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size, bounds)
                val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
                var sample = 1
                while (longEdge / (sample * 2) >= maxLongEdge) {
                    sample *= 2
                }
                var decoded: Bitmap? = null
                var attempt = 0
                while (decoded == null && attempt < 3) {
                    try {
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        decoded = BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size, opts)
                        if (decoded == null) break
                    } catch (_: OutOfMemoryError) {
                        System.gc()
                        sample *= 2
                        attempt++
                    }
                }
                val rawBitmap = decoded
                    ?: throw IllegalStateException("Failed to decode captured JPEG frame")
                orientAndDownscaleBitmap(
                    rawBitmap = rawBitmap,
                    rotationDegrees = frame.rotationDegrees,
                    mirrorHorizontally = frame.mirrorHorizontally,
                    maxLongEdge = maxLongEdge,
                )
            }
        }

        private fun orientAndDownscaleBitmap(
            rawBitmap: Bitmap,
            rotationDegrees: Int,
            mirrorHorizontally: Boolean,
            maxLongEdge: Int,
        ): Bitmap {
            val srcLong = maxOf(rawBitmap.width, rawBitmap.height)
            val downscale = if (srcLong > maxLongEdge) maxLongEdge.toFloat() / srcLong else 1f
            if (rotationDegrees == 0 && !mirrorHorizontally && downscale >= 0.99f) {
                return rawBitmap
            }
            val matrix = Matrix().apply {
                if (downscale < 0.99f) postScale(downscale, downscale)
                if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
                if (mirrorHorizontally) postScale(-1f, 1f)
            }
            return Bitmap.createBitmap(
                rawBitmap,
                0,
                0,
                rawBitmap.width,
                rawBitmap.height,
                matrix,
                true,
            ).also {
                if (it !== rawBitmap && !rawBitmap.isRecycled) rawBitmap.recycle()
            }
        }
    }

    private fun mapFlashMode(flashMode: FlashMode): Int = when (flashMode) {
        FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
        FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
        FlashMode.ON -> ImageCapture.FLASH_MODE_ON
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        cont.resume(future.get())
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
        }

    fun release() {
        runCatching { cameraProvider?.unbindAll() }
        previewProbeBitmap?.let { if (!it.isRecycled) it.recycle() }
        previewProbeBitmap = null
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
    }
}
