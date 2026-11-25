package com.example.camera.processing

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.os.Build
import com.example.camera.hardware.CameraController
import com.example.camera.hardware.RawCapturedFrame
import com.example.camera.model.CameraMode
import com.example.camera.model.DetectedFace
import com.example.camera.model.HdrMode
import com.example.camera.model.HdrPipelineConfig
import com.example.camera.model.HdrProcessMetrics
import com.example.camera.model.KelvinColorMath
import com.example.camera.model.ManualExposureSettings
import com.example.camera.model.ToneColorMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

data class HdrProcessResult(
    val bitmap: Bitmap,
    val metrics: HdrProcessMetrics,
    val lumaHistogram: List<Int>,
)

/**
 * Multi-core LUT-accelerated HDR+ Enhanced computational pipeline:
 * 1. Parallel single-pass pixel extraction + ROI Laplacian sharpness reference selection
 * 2. Exposure-normalized 3-stage block/tile alignment for hand-shake compensation
 * 3. Precomputed LUT edge-preserving temporal merge for SNR boost & ghost rejection
 * 4. Smooth bilinearly-interpolated multi-pass Gaussian local tone mapping (zero blockiness)
 * 5. Precomputed LUT curves for Shadows, Highlights, bilateral Contrast & Sabre sharpening
 * 6. Fused ColorMatrix grading + inline histogram generation in parallel CPU strips
 */
class HdrPlusEngine {

    private val workerCount: Int
        get() = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

    private class ExtractedFrame(
        var pixels: IntArray?,
        val width: Int,
        val height: Int,
        val sharpness: Float,
        val exposureBiasEv: Float = 0f,
    )

    /**
     * Memory-bounded HDR+ entry point for compact [RawCapturedFrame] bursts:
     * decodes each frame one at a time, extracts its pixel array + ROI sharpness, and
     * IMMEDIATELY recycles the temporary Bitmap so full 12.5MP frames never sit as Bitmaps in RAM simultaneously.
     */
    suspend fun processRawBurst(
        rawFrames: List<RawCapturedFrame>,
        mode: CameraMode,
        hdrMode: HdrMode,
        manual: ManualExposureSettings,
        config: HdrPipelineConfig,
        focusPointNormalized: Pair<Float, Float>? = null,
        nightSightEnabled: Boolean = false,
        lowLightSeverity: Float = 0f,
        totalExposureSeconds: Float = 0f,
        isPixelExtensionCapture: Boolean = false,
        isBacklightActive: Boolean = false,
        detectedFaces: List<DetectedFace> = emptyList(),
    ): HdrProcessResult = withContext(Dispatchers.Default) {
        require(rawFrames.isNotEmpty()) { "At least one frame is required for processing" }
        val startNs = System.nanoTime()
        val activeFaces = if (config.faceDetectionEnabled) detectedFaces else emptyList()

        // Memory-safe streaming path for ALL multi-frame bursts at full 12.5MP resolution (4096px):
        // evaluates sharpness on compact 640px thumbnails (~1MB) prioritizing detected faces,
        // then decodes & merges full-resolution secondary frames one at a time so heap stays bounded.
        if (rawFrames.size > 1 && !isPixelExtensionCapture) {
            var refIndex = 0
            var refSharpness = -1f
            val hasZeroEvFrame = rawFrames.any { abs(it.exposureBiasEv) < 0.1f }
            val thumbSharpnesses = FloatArray(rawFrames.size)
            for (i in rawFrames.indices) {
                val raw = rawFrames[i]
                val ownsThumb = raw !is RawCapturedFrame.DecodedBitmap
                val thumb = CameraController.decodeRawFrame(raw, maxLongEdge = 640)
                try {
                    val tw = thumb.width
                    val th = thumb.height
                    val tpx = IntArray(tw * th)
                    thumb.getPixels(tpx, 0, tw, 0, 0, tw, th)
                    val s = computeRoiSharpness(tpx, tw, th, focusPointNormalized, activeFaces)
                    thumbSharpnesses[i] = s
                    val eligibleAsRef = !hasZeroEvFrame || abs(raw.exposureBiasEv) < 0.1f
                    if (eligibleAsRef && s > refSharpness) {
                        refSharpness = s
                        refIndex = i
                    }
                } finally {
                    if (ownsThumb && !thumb.isRecycled) thumb.recycle()
                }
            }

            val refRaw = rawFrames[refIndex]
            val ownsRefBmp = refRaw !is RawCapturedFrame.DecodedBitmap
            val refBmp = CameraController.decodeRawFrame(refRaw, maxLongEdge = MAX_PROCESS_LONG_EDGE)
            val width: Int
            val height: Int
            val mergedPixels: IntArray
            val hasExposureBrackets = !nightSightEnabled && rawFrames.any { abs(it.exposureBiasEv) >= 0.35f }
            // When fusing multi-exposure brackets, synthesize the Ultra HDR gainmap from the fused
            // pixels rather than cloning the clipped 0 EV single-frame gainmap.
            val wantNativeGainmap = !nightSightEnabled && !hasExposureBrackets && config.ultraHdrEnabled && hdrMode != HdrMode.OFF
            var nativeGainmap: Gainmap? = null
            try {
                val scaled = downscaleIfNeeded(refBmp, MAX_PROCESS_LONG_EDGE)
                try {
                    width = scaled.width
                    height = scaled.height
                    mergedPixels = IntArray(width * height)
                    scaled.getPixels(mergedPixels, 0, width, 0, 0, width, height)
                    if (wantNativeGainmap) {
                        nativeGainmap = cloneNativeGainmapIfPresent(scaled)
                    }
                } finally {
                    if (scaled !== refBmp && !scaled.isRecycled) scaled.recycle()
                }
            } finally {
                if (ownsRefBmp && !refBmp.isRecycled) refBmp.recycle()
            }

            try {
                val (alignmentConfidence, effectiveFramesMerged) = if (hasExposureBrackets && hdrMode != HdrMode.OFF) {
                    fuseExposureBracketsStreaming(
                        mergedPixels = mergedPixels,
                        rawFrames = rawFrames,
                        refIndex = refIndex,
                        width = width,
                        height = height,
                        config = config,
                        detectedFaces = activeFaces,
                    )
                } else if (hdrMode != HdrMode.OFF || nightSightEnabled) {
                    mergeRawFramesStreaming(
                        mergedPixels = mergedPixels,
                        rawFrames = rawFrames,
                        thumbSharpnesses = thumbSharpnesses,
                        refIndex = refIndex,
                        refSharpness = refSharpness,
                        width = width,
                        height = height,
                        config = config,
                        nightSightEnabled = nightSightEnabled,
                        detectedFaces = activeFaces,
                    )
                } else {
                    1.0f to 1
                }

                return@withContext finalizeMergedPixels(
                    mergedPixels = mergedPixels,
                    width = width,
                    height = height,
                    alignmentConfidence = alignmentConfidence,
                    effectiveFramesMerged = effectiveFramesMerged,
                    startNs = startNs,
                    hdrMode = hdrMode,
                    manual = manual,
                    config = config,
                    nightSightEnabled = nightSightEnabled,
                    totalExposureSeconds = totalExposureSeconds,
                    isPixelExtensionCapture = false,
                    nativeGainmap = nativeGainmap,
                    isBacklightActive = isBacklightActive,
                    focusPointNormalized = focusPointNormalized,
                    detectedFaces = activeFaces,
                ).also {
                    nativeGainmap = null
                }
            } finally {
                recycleGainmap(nativeGainmap)
            }
        }

        val extracted = ArrayList<ExtractedFrame>(1)
        val raw = rawFrames[0]
        val ownsBmp = raw !is RawCapturedFrame.DecodedBitmap
        val bmp = CameraController.decodeRawFrame(raw, maxLongEdge = MAX_PROCESS_LONG_EDGE)
        val wantNativeGainmap = !nightSightEnabled && config.ultraHdrEnabled &&
            (hdrMode != HdrMode.OFF || isPixelExtensionCapture)
        var nativeGainmap: Gainmap? = null
        try {
            val scaled = downscaleIfNeeded(bmp, MAX_PROCESS_LONG_EDGE)
            try {
                val w = scaled.width
                val h = scaled.height
                val px = IntArray(w * h)
                scaled.getPixels(px, 0, w, 0, 0, w, h)
                if (wantNativeGainmap) {
                    nativeGainmap = cloneNativeGainmapIfPresent(scaled)
                }
                extracted.add(ExtractedFrame(px, w, h, 1f, raw.exposureBiasEv))
            } finally {
                if (scaled !== bmp && !scaled.isRecycled) scaled.recycle()
            }
        } finally {
            if (ownsBmp && !bmp.isRecycled) bmp.recycle()
        }

        try {
            processExtractedFrames(
                extracted = extracted,
                startNs = startNs,
                mode = mode,
                hdrMode = hdrMode,
                manual = manual,
                config = config,
                nightSightEnabled = nightSightEnabled,
                lowLightSeverity = lowLightSeverity,
                totalExposureSeconds = totalExposureSeconds,
                isPixelExtensionCapture = isPixelExtensionCapture,
                nativeGainmap = nativeGainmap,
                isBacklightActive = isBacklightActive,
                focusPointNormalized = focusPointNormalized,
                detectedFaces = activeFaces,
            ).also {
                nativeGainmap = null
            }
        } finally {
            recycleGainmap(nativeGainmap)
        }
    }

    suspend fun processBurst(
        frames: List<Bitmap>,
        mode: CameraMode,
        hdrMode: HdrMode,
        manual: ManualExposureSettings,
        config: HdrPipelineConfig,
        focusPointNormalized: Pair<Float, Float>? = null,
        nightSightEnabled: Boolean = false,
        lowLightSeverity: Float = 0f,
        totalExposureSeconds: Float = 0f,
        isPixelExtensionCapture: Boolean = false,
        exposureBiasEvs: FloatArray? = null,
        isBacklightActive: Boolean = false,
        detectedFaces: List<DetectedFace> = emptyList(),
    ): HdrProcessResult = withContext(Dispatchers.Default) {
        require(frames.isNotEmpty()) { "At least one frame is required for processing" }
        val startNs = System.nanoTime()
        val activeFaces = if (config.faceDetectionEnabled) detectedFaces else emptyList()

        val extracted = ArrayList<ExtractedFrame>(frames.size)
        for (i in frames.indices) {
            val bmp = frames[i]
            val scaled = downscaleIfNeeded(bmp, MAX_PROCESS_LONG_EDGE)
            try {
                val w = scaled.width
                val h = scaled.height
                val px = IntArray(w * h)
                scaled.getPixels(px, 0, w, 0, 0, w, h)
                val sharp = if (frames.size > 1 && !isPixelExtensionCapture) {
                    computeRoiSharpness(px, w, h, focusPointNormalized, activeFaces)
                } else 1f
                val biasEv = exposureBiasEvs?.getOrNull(i) ?: 0f
                extracted.add(ExtractedFrame(px, w, h, sharp, biasEv))
            } finally {
                if (scaled !== bmp && !scaled.isRecycled) scaled.recycle()
            }
        }

        processExtractedFrames(
            extracted = extracted,
            startNs = startNs,
            mode = mode,
            hdrMode = hdrMode,
            manual = manual,
            config = config,
            nightSightEnabled = nightSightEnabled,
            lowLightSeverity = lowLightSeverity,
            totalExposureSeconds = totalExposureSeconds,
            isPixelExtensionCapture = isPixelExtensionCapture,
            isBacklightActive = isBacklightActive,
            focusPointNormalized = focusPointNormalized,
            detectedFaces = activeFaces,
        )
    }

    private suspend fun processExtractedFrames(
        extracted: MutableList<ExtractedFrame>,
        startNs: Long,
        mode: CameraMode,
        hdrMode: HdrMode,
        manual: ManualExposureSettings,
        config: HdrPipelineConfig,
        nightSightEnabled: Boolean = false,
        lowLightSeverity: Float = 0f,
        totalExposureSeconds: Float = 0f,
        isPixelExtensionCapture: Boolean = false,
        nativeGainmap: Gainmap? = null,
        isBacklightActive: Boolean = false,
        focusPointNormalized: Pair<Float, Float>? = null,
        detectedFaces: List<DetectedFace> = emptyList(),
    ): HdrProcessResult {
        val hasZeroEvFrame = extracted.any { abs(it.exposureBiasEv) < 0.1f }
        var refIndex = 0
        var refSharpness = -1f
        for (i in extracted.indices) {
            val eligible = !hasZeroEvFrame || abs(extracted[i].exposureBiasEv) < 0.1f
            if (eligible && extracted[i].sharpness > refSharpness) {
                refSharpness = extracted[i].sharpness
                refIndex = i
            }
        }

        val refFrame = extracted[refIndex]
        val width = refFrame.width
        val height = refFrame.height
        val mergedPixels = requireNotNull(refFrame.pixels)
        // Release reference from list wrapper; secondary frames are cleared inside mergeFramesInPlace
        refFrame.pixels = null

        val hasExposureBrackets = !isPixelExtensionCapture &&
            !nightSightEnabled &&
            hdrMode != HdrMode.OFF &&
            extracted.size > 1 &&
            extracted.any { abs(it.exposureBiasEv) >= 0.35f }

        val (alignmentConfidence, effectiveFramesMerged) = if (hasExposureBrackets) {
            fuseExposureBracketsInPlace(
                mergedPixels = mergedPixels,
                extracted = extracted,
                refIndex = refIndex,
                width = width,
                height = height,
                config = config,
                detectedFaces = detectedFaces,
            )
        } else if (!isPixelExtensionCapture &&
            extracted.size > 1 &&
            (hdrMode != HdrMode.OFF || nightSightEnabled)
        ) {
            mergeFramesInPlace(
                mergedPixels = mergedPixels,
                extracted = extracted,
                refIndex = refIndex,
                refSharpness = refSharpness,
                width = width,
                height = height,
                config = config,
                nightSightEnabled = nightSightEnabled,
                detectedFaces = detectedFaces,
            )
        } else {
            // Immediately release any unused secondary frame pixel buffers
            for (f in extracted) f.pixels = null
            1.0f to 1
        }
        extracted.clear()

        return finalizeMergedPixels(
            mergedPixels = mergedPixels,
            width = width,
            height = height,
            alignmentConfidence = alignmentConfidence,
            effectiveFramesMerged = effectiveFramesMerged,
            startNs = startNs,
            hdrMode = hdrMode,
            manual = manual,
            config = config,
            nightSightEnabled = nightSightEnabled,
            totalExposureSeconds = totalExposureSeconds,
            isPixelExtensionCapture = isPixelExtensionCapture,
            nativeGainmap = nativeGainmap,
            isBacklightActive = isBacklightActive,
            focusPointNormalized = focusPointNormalized,
            detectedFaces = detectedFaces,
        )
    }

    private suspend fun finalizeMergedPixels(
        mergedPixels: IntArray,
        width: Int,
        height: Int,
        alignmentConfidence: Float,
        effectiveFramesMerged: Int,
        startNs: Long,
        hdrMode: HdrMode,
        manual: ManualExposureSettings,
        config: HdrPipelineConfig,
        nightSightEnabled: Boolean,
        totalExposureSeconds: Float,
        isPixelExtensionCapture: Boolean = false,
        nativeGainmap: Gainmap? = null,
        isBacklightActive: Boolean = false,
        focusPointNormalized: Pair<Float, Float>? = null,
        detectedFaces: List<DetectedFace> = emptyList(),
    ): HdrProcessResult {
        val totalPixels = width * height

        // In custom Night Sight mode (non-extension), run an edge-preserving chroma-only spatial
        // denoise pass before tone lift so high-ISO color speckles disappear while luma stays crisp.
        if (nightSightEnabled && !isPixelExtensionCapture && width >= 16 && height >= 16) {
            denoiseChromaInPlace(mergedPixels, width, height)
        }

        // Measure actual merged frame luminance so custom Night Sight ONLY brightens if the merged
        // image is genuinely still dark, never double-brightening well-exposed long exposures.
        var adaptiveNightGain = 1.0f
        var adaptiveNightShadowLift = 0f
        val isCustomNightSight = nightSightEnabled && !isPixelExtensionCapture
        if (isCustomNightSight) {
            val stats = estimateLumaStats(mergedPixels, width, height)
            val meanLuma = stats.mean
            val p96Luma = stats.p96
            val clippedPct = stats.clippedPercent
            when {
                meanLuma > 0.46f || (clippedPct > 6f && meanLuma > 0.36f) -> {
                    // Hardware captured hot or clipped — pull exposure back cleanly to prevent blown highlights
                    val meanPull = (0.38f / meanLuma.coerceAtLeast(0.39f)).coerceIn(0.55f, 1.0f)
                    val clipPull = if (clippedPct > 6f) {
                        (1f - ((clippedPct - 6f) / 45f)).coerceIn(0.72f, 0.96f)
                    } else 1.0f
                    adaptiveNightGain = min(meanPull, clipPull).coerceIn(0.55f, 1.0f)
                    adaptiveNightShadowLift = 0f
                }
                meanLuma < 0.28f -> {
                    val targetMean = 0.28f
                    val rawGain = (targetMean / meanLuma.coerceAtLeast(0.06f)).coerceIn(1.0f, 1.65f)
                    // Strictly protect highlights using p96 (top 4%): drop global gain to 1.0 when
                    // bright areas already approach clipping, letting shadow-only lift brighten darks safely.
                    val highlightCap = if (clippedPct > 1.5f) {
                        1.0f
                    } else {
                        (0.84f / p96Luma.coerceAtLeast(0.30f)).coerceIn(1.0f, 1.65f)
                    }
                    adaptiveNightGain = min(rawGain, highlightCap)
                    // Smooth shadow-only lift that tapers to zero at highlights (v -> 1.0)
                    val darknessRatio = ((0.28f - meanLuma) / 0.22f).coerceIn(0f, 1f)
                    adaptiveNightShadowLift = darknessRatio * 0.36f
                }
                else -> {
                    // Already well-exposed in [0.28, 0.46]: keep natural exposure, zero extra lift!
                    adaptiveNightGain = 1.0f
                    adaptiveNightShadowLift = 0f
                }
            }
        }

        // Automatically disable all HDR modes (HDR+, HDR+ Enhanced, Ultra HDR) during Low Light / Night Sight.
        // When Pixel Camera Services (Extensions) handled capture, skip redundant CPU HDR tone compression & sharpening.
        val effectiveHdrMode = if (nightSightEnabled || isPixelExtensionCapture) HdrMode.OFF else hdrMode
        val hdrStrength = when (effectiveHdrMode) {
            HdrMode.OFF -> 0f
            HdrMode.HDR_PLUS -> 0.50f
            HdrMode.HDR_PLUS_ENHANCED -> 0.78f
        }
        // Avoid darkening low-light scenes with Cinematic negative EV/shadow bias during Night Sight
        val cinematicOn = config.cinematicEffectEnabled && !nightSightEnabled
        val effectiveShadowSlider = (manual.shadowBoost + if (cinematicOn) ToneColorMath.CINEMATIC_SHADOW_OFFSET else 0f)
            .coerceIn(-1f, 1f)
        val shadowLiftAmount = (effectiveShadowSlider * 0.45f + adaptiveNightShadowLift).coerceIn(-0.55f, 0.85f)
        val highlightRecoverBase = (config.highlightRecovery * hdrStrength).coerceIn(0f, 1f)
        val highlightAdjust = manual.highlightBoost.coerceIn(-1f, 1f)
        val nightContrastBoost = if (isCustomNightSight && adaptiveNightGain >= 1.0f) 0.03f else 0f
        val contrastAmount = (manual.contrastAdjust + if (cinematicOn) ToneColorMath.CINEMATIC_CONTRAST_OFFSET else 0f + nightContrastBoost)
            .coerceIn(-1f, 1f)
        val vibrancyAmount = (manual.vibrancyAdjust + if (cinematicOn) ToneColorMath.CINEMATIC_VIBRANCY_OFFSET else 0f + if (isCustomNightSight) 0.12f else 0f)
            .coerceIn(-1f, 1f)
        val baseLocalContrast = (config.localToneMapping * hdrStrength * 0.24f).coerceIn(0f, 0.35f)
        val backlightClarityFactor = if (isBacklightActive && !nightSightEnabled) (config.backlightShadowClarity * 0.45f).coerceIn(0f, 0.65f) else 0f
        val backlightDehazeFactor = if (isBacklightActive && !nightSightEnabled) (config.backlightDehazeStrength * 0.08f).coerceIn(0f, 0.12f) else 0f
        val localContrastStrength = if (isBacklightActive && !nightSightEnabled) {
            (baseLocalContrast * (1f + backlightClarityFactor * 0.65f)).coerceIn(0f, 0.45f)
        } else baseLocalContrast

        val sharpenAmount = when {
            isPixelExtensionCapture -> 0f
            nightSightEnabled -> (config.sabreSharpening * 0.78f).coerceIn(0f, 0.90f)
            isBacklightActive -> (config.sabreSharpening * (0.92f + backlightClarityFactor * 0.15f)).coerceIn(0f, 1.0f)
            else -> (config.sabreSharpening * 0.92f).coerceIn(0f, 1.0f)
        }

        val toneLut = buildToneLut(
            shadowLift = shadowLiftAmount,
            highlightRecover = highlightRecoverBase,
            highlightAdjust = highlightAdjust,
            backlightDehaze = backlightDehazeFactor,
            backlightClarity = backlightClarityFactor,
        )

        val useLocalTone = localContrastStrength > 0.005f
        val lowW = 48
        val lowH = ((48f * height) / width).toInt().coerceIn(24, 64)
        val lowLuma = if (useLocalTone) {
            computeSmoothGaussianLumaField(mergedPixels, width, height, lowW, lowH)
        } else null
        val edgeStopLut = if (useLocalTone) buildNormalizedExpLut(sigma = 0.14f) else null

        val (wbR, wbG, wbB) = manual.whiteBalanceKelvin?.let { KelvinColorMath.kelvinToRgbGains(it) }
            ?: Triple(1f, 1f, 1f)

        val cinematicEvBias = if (cinematicOn) ToneColorMath.CINEMATIC_EV_BIAS else 0f
        val baseEvGain = if (!manual.isManualExposureActive && effectiveHdrMode == HdrMode.OFF && !cinematicOn && !nightSightEnabled) {
            1f
        } else {
            2f.pow((manual.exposureCompensationEv.coerceIn(-2.5f, 2.5f) * 0.18f) + cinematicEvBias)
        }
        val evGain = baseEvGain * adaptiveNightGain

        val cm = ToneColorMath.buildPreviewColorMatrix(
            computationalEvDelta = 0f,
            shadowSlider = 0f,
            highlightSlider = 0f,
            contrastSlider = contrastAmount,
            vibrancySlider = vibrancyAmount * 0.65f,
            kelvinOverride = null,
            cinematicEffect = false,
        ).array
        val m0 = cm[0]; val m1 = cm[1]; val m2 = cm[2]; val m4 = cm[4]
        val m5 = cm[5]; val m6 = cm[6]; val m7 = cm[7]; val m9 = cm[9]
        val m10 = cm[10]; val m11 = cm[11]; val m12 = cm[12]; val m14 = cm[14]

        val useNativeGainmap = nativeGainmap != null &&
            !nightSightEnabled &&
            config.ultraHdrEnabled &&
            (effectiveHdrMode != HdrMode.OFF || isPixelExtensionCapture) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

        val buildGainmap = !useNativeGainmap &&
            !nightSightEnabled &&
            config.ultraHdrEnabled &&
            effectiveHdrMode != HdrMode.OFF &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            width >= 32 &&
            height >= 32
        val hdrHeadroomPlane = if (buildGainmap) FloatArray(totalPixels) else null

        val wantMotionDeblur = !isPixelExtensionCapture &&
            config.faceDeblurEnabled &&
            config.motionDeblurStrength > 0.05f &&
            width >= 24 &&
            height >= 24
        val needSpatialPass = sharpenAmount > 0.01f || wantMotionDeblur
        val lumaPlane = if (needSpatialPass) FloatArray(totalPixels) else null
        var appliedMotionDeblurStrength = 0f
        var appliedFaceDeblur = false
        val xScale = (lowW - 1).toFloat() / max(1, width - 1)
        val yScale = (lowH - 1).toFloat() / max(1, height - 1)
        val histStepX = max(1, width / 100)
        val histStepY = max(1, height / 100)
        val applyVibInline = abs(vibrancyAmount) > 0.01f

        var outputPixels: IntArray = mergedPixels
        val histogramBins = if (!needSpatialPass) {
            // Single fused parallel pass: local tone + LUT + vibrancy + ColorMatrix + inline histogram
            val chunkHistograms = forEachRowChunkWithResult(height) { yStart, yEnd ->
                val localBins = IntArray(32)
                for (y in yStart until yEnd) {
                    val sampleRow = (y % histStepY) == 0
                    val rowOff = y * width
                    var lowRow0 = 0
                    var lowRow1 = 0
                    var wy = 0f
                    if (lowLuma != null) {
                        val fy = y * yScale
                        val y0 = fy.toInt().coerceIn(0, lowH - 2)
                        wy = fy - y0
                        lowRow0 = y0 * lowW
                        lowRow1 = (y0 + 1) * lowW
                    }

                    for (x in 0 until width) {
                        val i = rowOff + x
                        val p = mergedPixels[i]
                        var r = ((p shr 16) and 0xFF) * (1f / 255f) * wbR * evGain
                        var g = ((p shr 8) and 0xFF) * (1f / 255f) * wbG * evGain
                        var b = (p and 0xFF) * (1f / 255f) * wbB * evGain

                        val luma = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0.0001f, 2.5f)
                        var mappedLuma = if (isCustomNightSight && luma > 0.76f) {
                            val excess = luma - 0.76f
                            (0.76f + 0.24f * (excess / (0.24f + excess))).coerceIn(0f, 1f)
                        } else {
                            luma.coerceIn(0f, 1f)
                        }

                        if (lowLuma != null && edgeStopLut != null) {
                            val fx = x * xScale
                            val x0 = fx.toInt().coerceIn(0, lowW - 2)
                            val wx = fx - x0
                            val l00 = lowLuma[lowRow0 + x0]
                            val l10 = lowLuma[lowRow0 + x0 + 1]
                            val l01 = lowLuma[lowRow1 + x0]
                            val l11 = lowLuma[lowRow1 + x0 + 1]
                            val top = l00 + (l10 - l00) * wx
                            val bot = l01 + (l11 - l01) * wx
                            val localAvg = top + (bot - top) * wy

                            val rawDetail = mappedLuma - localAvg
                            val eIdx = (abs(rawDetail) * 255f).toInt().coerceIn(0, 255)
                            val detail = rawDetail * edgeStopLut[eIdx]
                            mappedLuma = (mappedLuma + detail * localContrastStrength).coerceIn(0f, 1f)
                        }

                        val lutPos = mappedLuma * 255f
                        val lutIdx = lutPos.toInt().coerceIn(0, 254)
                        val lutFrac = lutPos - lutIdx
                        val targetLuma = toneLut[lutIdx] + (toneLut[lutIdx + 1] - toneLut[lutIdx]) * lutFrac

                        if (hdrHeadroomPlane != null) {
                            val compressedDelta = (luma - targetLuma).coerceAtLeast(0f) * 2.1f
                            val specularBoost = ((luma - 0.54f).coerceAtLeast(0f) / 0.46f) * (hdrStrength * 1.1f)
                            hdrHeadroomPlane[i] = (compressedDelta + specularBoost).coerceIn(0f, 1f)
                        }

                        val scale = targetLuma / luma
                        var nr = r * scale
                        var ng = g * scale
                        var nb = b * scale

                        if (applyVibInline) {
                            val maxC = max(nr, max(ng, nb))
                            val minC = min(nr, min(ng, nb))
                            val sat = if (maxC > 0.001f) (maxC - minC) / maxC else 0f
                            val vibScale = (1f + vibrancyAmount * (1f - sat * 0.45f) * 0.42f).coerceIn(0.25f, 1.85f)
                            nr = targetLuma + (nr - targetLuma) * vibScale
                            ng = targetLuma + (ng - targetLuma) * vibScale
                            nb = targetLuma + (nb - targetLuma) * vibScale
                        }

                        val fr = (nr * 255f).coerceIn(0f, 255f)
                        val fg = (ng * 255f).coerceIn(0f, 255f)
                        val fb = (nb * 255f).coerceIn(0f, 255f)

                        val ir = (m0 * fr + m1 * fg + m2 * fb + m4).toInt().coerceIn(0, 255)
                        val ig = (m5 * fr + m6 * fg + m7 * fb + m9).toInt().coerceIn(0, 255)
                        val ib = (m10 * fr + m11 * fg + m12 * fb + m14).toInt().coerceIn(0, 255)
                        mergedPixels[i] = (0xFF shl 24) or (ir shl 16) or (ig shl 8) or ib

                        if (sampleRow && (x % histStepX) == 0) {
                            val outL = (0.299f * ir + 0.587f * ig + 0.114f * ib).toInt().coerceIn(0, 255)
                            localBins[outL shr 3]++
                        }
                    }
                }
                localBins
            }
            mergeHistograms(chunkHistograms)
        } else {
            // Pass 1 (Parallel): Tone mapping + Vibrancy + HDR Headroom Capture
            forEachRowChunk(height) { yStart, yEnd ->
                for (y in yStart until yEnd) {
                    val rowOff = y * width
                    var lowRow0 = 0
                    var lowRow1 = 0
                    var wy = 0f
                    if (lowLuma != null) {
                        val fy = y * yScale
                        val y0 = fy.toInt().coerceIn(0, lowH - 2)
                        wy = fy - y0
                        lowRow0 = y0 * lowW
                        lowRow1 = (y0 + 1) * lowW
                    }

                    for (x in 0 until width) {
                        val i = rowOff + x
                        val p = mergedPixels[i]
                        val r = ((p shr 16) and 0xFF) * (1f / 255f) * wbR * evGain
                        val g = ((p shr 8) and 0xFF) * (1f / 255f) * wbG * evGain
                        val b = (p and 0xFF) * (1f / 255f) * wbB * evGain

                        val luma = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0.0001f, 2.5f)
                        var mappedLuma = if (isCustomNightSight && luma > 0.76f) {
                            val excess = luma - 0.76f
                            (0.76f + 0.24f * (excess / (0.24f + excess))).coerceIn(0f, 1f)
                        } else {
                            luma.coerceIn(0f, 1f)
                        }

                        if (lowLuma != null && edgeStopLut != null) {
                            val fx = x * xScale
                            val x0 = fx.toInt().coerceIn(0, lowW - 2)
                            val wx = fx - x0
                            val l00 = lowLuma[lowRow0 + x0]
                            val l10 = lowLuma[lowRow0 + x0 + 1]
                            val l01 = lowLuma[lowRow1 + x0]
                            val l11 = lowLuma[lowRow1 + x0 + 1]
                            val top = l00 + (l10 - l00) * wx
                            val bot = l01 + (l11 - l01) * wx
                            val localAvg = top + (bot - top) * wy

                            val rawDetail = mappedLuma - localAvg
                            val eIdx = (abs(rawDetail) * 255f).toInt().coerceIn(0, 255)
                            val detail = rawDetail * edgeStopLut[eIdx]
                            mappedLuma = (mappedLuma + detail * localContrastStrength).coerceIn(0f, 1f)
                        }

                        val lutPos = mappedLuma * 255f
                        val lutIdx = lutPos.toInt().coerceIn(0, 254)
                        val lutFrac = lutPos - lutIdx
                        val targetLuma = toneLut[lutIdx] + (toneLut[lutIdx + 1] - toneLut[lutIdx]) * lutFrac

                        if (lumaPlane != null) lumaPlane[i] = targetLuma
                        if (hdrHeadroomPlane != null) {
                            val compressedDelta = (luma - targetLuma).coerceAtLeast(0f) * 2.1f
                            val specularBoost = ((luma - 0.54f).coerceAtLeast(0f) / 0.46f) * (hdrStrength * 1.1f)
                            hdrHeadroomPlane[i] = (compressedDelta + specularBoost).coerceIn(0f, 1f)
                        }

                        val scale = targetLuma / luma
                        var nr = r * scale
                        var ng = g * scale
                        var nb = b * scale

                        if (applyVibInline) {
                            val maxC = max(nr, max(ng, nb))
                            val minC = min(nr, min(ng, nb))
                            val sat = if (maxC > 0.001f) (maxC - minC) / maxC else 0f
                            val vibScale = (1f + vibrancyAmount * (1f - sat * 0.45f) * 0.42f).coerceIn(0.25f, 1.85f)
                            nr = targetLuma + (nr - targetLuma) * vibScale
                            ng = targetLuma + (ng - targetLuma) * vibScale
                            nb = targetLuma + (nb - targetLuma) * vibScale
                        }

                        val ir = (nr * 255f).toInt().coerceIn(0, 255)
                        val ig = (ng * 255f).toInt().coerceIn(0, 255)
                        val ib = (nb * 255f).toInt().coerceIn(0, 255)
                        mergedPixels[i] = (0xFF shl 24) or (ir shl 16) or (ig shl 8) or ib
                    }
                }
            }

            // Pass 1.5 (Parallel): Blur-Gated Linear-Light Directional + Optical Deblur (Scene & Face-Localized)
            // Runs regularized shock-bounded Richardson-Lucy deconvolution ONLY when real softness or motion blur
            // is detected, localizing to a compact feathered face ROI when only the face moved on a sharp scene.
            val facePixelBounds = buildFacePixelBounds(detectedFaces, width, height, padFraction = 0.14f)
            if (wantMotionDeblur && lumaPlane != null) {
                val sceneEstimate = estimateMotionBlur(
                    lumaPlane = lumaPlane,
                    width = width,
                    height = height,
                    focusPointNormalized = focusPointNormalized,
                    detectedFaces = emptyList(),
                    excludeFaceBounds = facePixelBounds,
                )
                val faceEstimate = if (detectedFaces.isNotEmpty()) {
                    estimateMotionBlur(
                        lumaPlane = lumaPlane,
                        width = width,
                        height = height,
                        focusPointNormalized = focusPointNormalized,
                        detectedFaces = detectedFaces,
                    )
                } else null

                val faceNeedsDeblur = faceEstimate != null && faceEstimate.blurSeverity >= 0.16f
                val sceneThreshold = if (isBacklightActive && !nightSightEnabled) 0.14f else 0.20f
                val sceneNeedsDeblur = sceneEstimate.blurSeverity >= sceneThreshold

                if (faceNeedsDeblur || sceneNeedsDeblur) {
                    // When the face is softer than a sharp background (head/expression motion),
                    // run deblur ONLY inside the feathered face ROI to protect background sharpness and save RAM/CPU.
                    val faceOnlyMode = faceEstimate != null &&
                        faceNeedsDeblur &&
                        (!sceneNeedsDeblur ||
                            (sceneEstimate.blurSeverity < 0.26f &&
                                faceEstimate.blurSeverity > sceneEstimate.blurSeverity + 0.14f))

                    val chosenEstimate = when {
                        faceOnlyMode -> faceEstimate
                        faceEstimate != null && faceEstimate.blurSeverity > sceneEstimate.blurSeverity -> faceEstimate
                        else -> sceneEstimate
                    }

                    val baseUserDeblur = config.motionDeblurStrength.coerceIn(0f, 1f)
                    val nightScale = if (nightSightEnabled) 0.42f else 1.0f
                    val backlightBoost = if (isBacklightActive && !nightSightEnabled) 0.10f else 0f
                    val effectiveDeblur = ((chosenEstimate.blurSeverity + backlightBoost) *
                        baseUserDeblur * nightScale).coerceIn(0f, 1.0f)

                    if (effectiveDeblur > 0.10f) {
                        applyMotionDeblurPass(
                            mergedPixels = mergedPixels,
                            lumaPlane = lumaPlane,
                            width = width,
                            height = height,
                            estimate = chosenEstimate,
                            deblurStrength = effectiveDeblur,
                            isBacklightActive = isBacklightActive && !nightSightEnabled,
                            nightSightEnabled = nightSightEnabled,
                            faceBounds = facePixelBounds,
                            faceOnlyMode = faceOnlyMode,
                        )
                        appliedMotionDeblurStrength = effectiveDeblur
                        appliedFaceDeblur = facePixelBounds.isNotEmpty() && (faceNeedsDeblur || faceOnlyMode)
                    }
                }
            }

            // Pass 2 (Parallel): 2-Scale Edge-Preserving Sabre Deconvolution Sharpening + Fused ColorMatrix
            // Gently attenuate Sabre when Motion Deblur already recovered micro-edges to prevent double-sharpening.
            val effectiveSharpenAmount = (sharpenAmount * (1f - appliedMotionDeblurStrength * 0.38f)).coerceAtLeast(0f)
            val finalPixels = IntArray(totalPixels)
            val sharpenEdgeLut = if (effectiveSharpenAmount > 0.01f) buildNormalizedExpLut(sigma = 0.13f) else null
            val useMediumScale = width >= 32 && height >= 32

            val chunkHistograms = forEachRowChunkWithResult(height) { yStart, yEnd ->
                val localBins = IntArray(32)
                for (y in yStart until yEnd) {
                    val sampleRow = (y % histStepY) == 0
                    val rowOff = y * width
                    val yPrev = (y - 1).coerceAtLeast(0) * width
                    val yNext = (y + 1).coerceAtMost(height - 1) * width
                    val y2Prev = (y - 2).coerceAtLeast(0) * width
                    val y2Next = (y + 2).coerceAtMost(height - 1) * width

                    for (x in 0 until width) {
                        val i = rowOff + x
                        val p = mergedPixels[i]
                        var r = ((p shr 16) and 0xFF).toFloat()
                        var g = ((p shr 8) and 0xFF).toFloat()
                        var b = (p and 0xFF).toFloat()

                        if (lumaPlane != null && sharpenEdgeLut != null && effectiveSharpenAmount > 0.01f) {
                            val xPrev = (x - 1).coerceAtLeast(0)
                            val xNext = (x + 1).coerceAtMost(width - 1)
                            val centerL = lumaPlane[i]

                            // 3x3 Gaussian-weighted fine Laplacian kernel + 8-neighbor bounds
                            val tl = lumaPlane[yPrev + xPrev]
                            val tc = lumaPlane[yPrev + x]
                            val tr = lumaPlane[yPrev + xNext]
                            val ml = lumaPlane[rowOff + xPrev]
                            val mr = lumaPlane[rowOff + xNext]
                            val bl = lumaPlane[yNext + xPrev]
                            val bc = lumaPlane[yNext + x]
                            val br = lumaPlane[yNext + xNext]

                            val gauss3 = (tl + tr + bl + br + 2f * (tc + bc + ml + mr) + 4f * centerL) * (1f / 16f)
                            val fineDetail = centerL - gauss3

                            // Noise-coring threshold: when backlit, lower coring on shadowed subject to preserve fine hair & skin micro-texture
                            val baseFineCoring = if (nightSightEnabled) 0.006f else 0.0028f
                            val fineCoring = if (isBacklightActive && centerL < 0.60f) {
                                (baseFineCoring * (1f - backlightClarityFactor * 0.40f)).coerceAtLeast(0.0014f)
                            } else baseFineCoring
                            val coredFine = if (abs(fineDetail) > fineCoring) fineDetail else 0f

                            // Subtle medium-scale (radius-2) optical micro-contrast
                            val medDetail = if (useMediumScale) {
                                val x2Prev = (x - 2).coerceAtLeast(0)
                                val x2Next = (x + 2).coerceAtMost(width - 1)
                                val medAvg = (lumaPlane[rowOff + x2Prev] + lumaPlane[rowOff + x2Next] +
                                    lumaPlane[y2Prev + x] + lumaPlane[y2Next + x]) * 0.25f
                                val md = centerL - medAvg
                                val medCoring = if (nightSightEnabled) 0.010f else 0.0055f
                                if (abs(md) > medCoring) md * 0.30f else 0f
                            } else 0f

                            val subjectClarityMultiplier = if (isBacklightActive && centerL < 0.65f) {
                                1f + backlightClarityFactor * (1f - centerL).coerceIn(0f, 1f) * 1.10f
                            } else 1f
                            // Smoothly protect face skin from excessive micro-contrast crunch while keeping eyes/hair crisp
                            val faceW = if (facePixelBounds.isNotEmpty()) faceMaskWeight(x, y, facePixelBounds) else 0f
                            val faceScale = if (faceW > 0.001f) {
                                val detailBlend = smoothstep(0.012f, 0.036f, abs(fineDetail))
                                val targetFaceScale = 0.50f + (1.12f - 0.50f) * detailBlend
                                1f + (targetFaceScale - 1f) * faceW
                            } else 1f
                            val combinedDiff = (coredFine * 1.75f + medDetail) * subjectClarityMultiplier * faceScale
                            if (combinedDiff != 0f) {
                                val edgeMask = sharpenEdgeLut[(abs(fineDetail) * 255f).toInt().coerceIn(0, 255)]
                                val minL = min(centerL, min(min(min(ml, mr), min(tc, bc)), min(min(tl, tr), min(bl, br))))
                                val maxL = max(centerL, max(max(max(ml, mr), max(tc, bc)), max(max(tl, tr), max(bl, br))))
                                // Asymmetric acutance clamp: crisp bright edge micro-contrast without dark ringing halos
                                val maxDarkOvershoot = 0.022f * effectiveSharpenAmount
                                val maxBrightOvershoot = 0.036f * effectiveSharpenAmount
                                val targetSharpenedL = (centerL + combinedDiff * effectiveSharpenAmount * edgeMask)
                                    .coerceIn((minL - maxDarkOvershoot).coerceAtLeast(0f), (maxL + maxBrightOvershoot).coerceAtMost(1f))
                                val delta = ((targetSharpenedL - centerL) * 255f).coerceIn(-28f, 28f)
                                r += delta
                                g += delta
                                b += delta
                            }
                        }

                        val fr = r.coerceIn(0f, 255f)
                        val fg = g.coerceIn(0f, 255f)
                        val fb = b.coerceIn(0f, 255f)

                        val ir = (m0 * fr + m1 * fg + m2 * fb + m4).toInt().coerceIn(0, 255)
                        val ig = (m5 * fr + m6 * fg + m7 * fb + m9).toInt().coerceIn(0, 255)
                        val ib = (m10 * fr + m11 * fg + m12 * fb + m14).toInt().coerceIn(0, 255)
                        finalPixels[i] = (0xFF shl 24) or (ir shl 16) or (ig shl 8) or ib

                        if (sampleRow && (x % histStepX) == 0) {
                            val outL = (0.299f * ir + 0.587f * ig + 0.114f * ib).toInt().coerceIn(0, 255)
                            localBins[outL shr 3]++
                        }
                    }
                }
                localBins
            }
            outputPixels = finalPixels
            mergeHistograms(chunkHistograms)
        }

        val gradedBitmap = Bitmap.createBitmap(outputPixels, width, height, Bitmap.Config.ARGB_8888)
        val hasUltraHdrGainmap = if (useNativeGainmap) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && nativeGainmap != null) {
                    gradedBitmap.gainmap = nativeGainmap
                    true
                } else {
                    false
                }
            }.getOrElse {
                recycleGainmap(nativeGainmap)
                false
            }
        } else {
            recycleGainmap(nativeGainmap)
            if (hdrHeadroomPlane != null) {
                attachUltraHdrGainmap(
                    baseBitmap = gradedBitmap,
                    headroomPlane = hdrHeadroomPlane,
                    width = width,
                    height = height,
                    maxBoostRatio = if (effectiveHdrMode == HdrMode.HDR_PLUS_ENHANCED) 3.2f else 2.5f,
                )
            } else {
                false
            }
        }

        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)

        val snrGainStops = if (effectiveFramesMerged > 1) {
            kotlin.math.log2(effectiveFramesMerged.toFloat()) * (if (nightSightEnabled) 0.85f else 0.65f)
        } else 0f
        val toneGainStops = hdrStrength * (1.2f + highlightRecoverBase * 0.9f) +
            if (nightSightEnabled) kotlin.math.log2(adaptiveNightGain.coerceAtLeast(1f)) else 0f
        val drGainStops = (snrGainStops + toneGainStops).coerceIn(0f, if (nightSightEnabled) 5.5f else 4.2f)

        return HdrProcessResult(
            bitmap = gradedBitmap,
            metrics = HdrProcessMetrics(
                framesMerged = effectiveFramesMerged,
                alignmentConfidence = alignmentConfidence,
                dynamicRangeGainStops = drGainStops,
                processingTimeMs = elapsedMs,
                highlightRecoveryApplied = highlightRecoverBase,
                shadowLiftApplied = shadowLiftAmount,
                isUltraHdr = hasUltraHdrGainmap,
                isNightSight = nightSightEnabled,
                totalExposureSeconds = totalExposureSeconds,
                isBacklightEnhanced = isBacklightActive && !nightSightEnabled,
                backlightDehazeApplied = backlightDehazeFactor,
                facesDetected = detectedFaces.size,
                faceDeblurApplied = appliedFaceDeblur,
                motionDeblurApplied = appliedMotionDeblurStrength,
            ),
            lumaHistogram = histogramBins,
        )
    }

    /**
     * Multi-scale Laplacian Pyramid Exposure Fusion (Mertens-style, streaming & memory-bounded)
     * for backlit multi-exposure bursts (e.g. 0 EV face anchor + -2/-4 EV highlight brackets + +1.5 EV shadow fill):
     * - Aligns each bracket frame to the 0 EV reference (ignoring clipped pixels during alignment).
     * - Computes per-pixel well-exposedness + saturation weights with clip suppression near 255,
     *   highlight/shadow role gating, and feathered face ghosting protection so well-exposed faces
     *   stay 100% crisp from the 0 EV frame while blown windows come cleanly from the dark frames.
     * - Streams frames one-by-one into a 5–6 level Laplacian pyramid (ShortArray L0 + FloatArray L1..5)
     *   so peak RAM stays bounded even at 12.5MP with zero cutout seams or halos around hair/windows.
     */
    private suspend fun fuseExposureBracketsStreaming(
        mergedPixels: IntArray,
        rawFrames: List<RawCapturedFrame>,
        refIndex: Int,
        width: Int,
        height: Int,
        config: HdrPipelineConfig,
        detectedFaces: List<DetectedFace> = emptyList(),
    ): Pair<Float, Int> {
        val totalPixels = width * height
        val refLumaPlane = FloatArray(totalPixels)
        val refIsEdge = BooleanArray(totalPixels)
        extractRefLumaAndEdges(mergedPixels, width, height, refLumaPlane, refIsEdge)

        val coarseW = 160
        val coarseH = 120
        val refThumb = createLumaThumbnailFromPlane(refLumaPlane, width, height, coarseW, coarseH)
        val refMeanLuma = meanOfArray(refThumb).coerceAtLeast(8f)
        val faceBounds = buildFacePixelBounds(detectedFaces, width, height, padFraction = 0.14f)

        val fusion = PyramidExposureFusion(width, height)
        fusion.accumulateFrame(
            pixels = mergedPixels,
            exposureBiasEv = rawFrames[refIndex].exposureBiasEv,
            isReference = true,
            refLumaPlane = refLumaPlane,
            refIsEdge = refIsEdge,
            faceBounds = faceBounds,
            globalDx = 0,
            globalDy = 0,
            tileShifts = null,
            tileCols = 8,
            tileRows = 6,
        )

        var confSum = 0f
        var alignedCount = 0
        var effectiveFramesMerged = 1

        for (fIdx in rawFrames.indices) {
            if (fIdx == refIndex) continue
            val raw = rawFrames[fIdx]
            val ownsBmp = raw !is RawCapturedFrame.DecodedBitmap
            val bmp = CameraController.decodeRawFrame(raw, maxLongEdge = MAX_PROCESS_LONG_EDGE)
            var otherPixels: IntArray? = null
            try {
                val scaled = downscaleIfNeeded(bmp, MAX_PROCESS_LONG_EDGE)
                try {
                    if (scaled.width == width && scaled.height == height) {
                        otherPixels = IntArray(totalPixels).also {
                            scaled.getPixels(it, 0, width, 0, 0, width, height)
                        }
                    }
                } finally {
                    if (scaled !== bmp && !scaled.isRecycled) scaled.recycle()
                }
            } finally {
                if (ownsBmp && !bmp.isRecycled) bmp.recycle()
            }

            val pixels = otherPixels ?: continue
            val aligned = alignFrameForFusion(
                otherPixels = pixels,
                otherExposureBiasEv = raw.exposureBiasEv,
                refLumaPlane = refLumaPlane,
                refThumb = refThumb,
                refMeanLuma = refMeanLuma,
                width = width,
                height = height,
                coarseW = coarseW,
                coarseH = coarseH,
                config = config,
            ) ?: continue

            fusion.accumulateFrame(
                pixels = pixels,
                exposureBiasEv = raw.exposureBiasEv,
                isReference = false,
                refLumaPlane = refLumaPlane,
                refIsEdge = refIsEdge,
                faceBounds = faceBounds,
                globalDx = aligned.globalDx,
                globalDy = aligned.globalDy,
                tileShifts = aligned.tileShifts,
                tileCols = aligned.tileCols,
                tileRows = aligned.tileRows,
            )
            confSum += aligned.confidence
            alignedCount++
            effectiveFramesMerged++
        }

        fusion.collapseToPixels(mergedPixels)
        val alignmentConfidence = if (alignedCount > 0) {
            (confSum / alignedCount).coerceIn(0.45f, 0.99f)
        } else {
            1.0f
        }
        return alignmentConfidence to effectiveFramesMerged
    }

    private suspend fun fuseExposureBracketsInPlace(
        mergedPixels: IntArray,
        extracted: MutableList<ExtractedFrame>,
        refIndex: Int,
        width: Int,
        height: Int,
        config: HdrPipelineConfig,
        detectedFaces: List<DetectedFace> = emptyList(),
    ): Pair<Float, Int> {
        val totalPixels = width * height
        val refLumaPlane = FloatArray(totalPixels)
        val refIsEdge = BooleanArray(totalPixels)
        extractRefLumaAndEdges(mergedPixels, width, height, refLumaPlane, refIsEdge)

        val coarseW = 160
        val coarseH = 120
        val refThumb = createLumaThumbnailFromPlane(refLumaPlane, width, height, coarseW, coarseH)
        val refMeanLuma = meanOfArray(refThumb).coerceAtLeast(8f)
        val faceBounds = buildFacePixelBounds(detectedFaces, width, height, padFraction = 0.14f)

        val fusion = PyramidExposureFusion(width, height)
        fusion.accumulateFrame(
            pixels = mergedPixels,
            exposureBiasEv = extracted[refIndex].exposureBiasEv,
            isReference = true,
            refLumaPlane = refLumaPlane,
            refIsEdge = refIsEdge,
            faceBounds = faceBounds,
            globalDx = 0,
            globalDy = 0,
            tileShifts = null,
            tileCols = 8,
            tileRows = 6,
        )

        var confSum = 0f
        var alignedCount = 0
        var effectiveFramesMerged = 1

        for (fIdx in extracted.indices) {
            if (fIdx == refIndex) continue
            val frame = extracted[fIdx]
            val otherPixels = frame.pixels
            frame.pixels = null
            if (otherPixels == null || frame.width != width || frame.height != height) continue

            val aligned = alignFrameForFusion(
                otherPixels = otherPixels,
                otherExposureBiasEv = frame.exposureBiasEv,
                refLumaPlane = refLumaPlane,
                refThumb = refThumb,
                refMeanLuma = refMeanLuma,
                width = width,
                height = height,
                coarseW = coarseW,
                coarseH = coarseH,
                config = config,
            ) ?: continue

            fusion.accumulateFrame(
                pixels = otherPixels,
                exposureBiasEv = frame.exposureBiasEv,
                isReference = false,
                refLumaPlane = refLumaPlane,
                refIsEdge = refIsEdge,
                faceBounds = faceBounds,
                globalDx = aligned.globalDx,
                globalDy = aligned.globalDy,
                tileShifts = aligned.tileShifts,
                tileCols = aligned.tileCols,
                tileRows = aligned.tileRows,
            )
            confSum += aligned.confidence
            alignedCount++
            effectiveFramesMerged++
        }

        fusion.collapseToPixels(mergedPixels)
        val alignmentConfidence = if (alignedCount > 0) {
            (confSum / alignedCount).coerceIn(0.45f, 0.99f)
        } else {
            1.0f
        }
        return alignmentConfidence to effectiveFramesMerged
    }

    private suspend fun extractRefLumaAndEdges(
        mergedPixels: IntArray,
        width: Int,
        height: Int,
        refLumaPlane: FloatArray,
        refIsEdge: BooleanArray,
    ) {
        forEachRowChunk(height) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val rowOff = y * width
                for (x in 0 until width) {
                    val i = rowOff + x
                    val p = mergedPixels[i]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    refLumaPlane[i] = 0.299f * r + 0.587f * g + 0.114f * b
                }
            }
        }
        forEachRowChunk(height) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val rowOff = y * width
                val yPrev = (y - 1).coerceAtLeast(0) * width
                val yNext = (y + 1).coerceAtMost(height - 1) * width
                for (x in 0 until width) {
                    val xPrev = (x - 1).coerceAtLeast(0)
                    val xNext = (x + 1).coerceAtMost(width - 1)
                    val gx = abs(refLumaPlane[rowOff + xNext] - refLumaPlane[rowOff + xPrev])
                    val gy = abs(refLumaPlane[yNext + x] - refLumaPlane[yPrev + x])
                    refIsEdge[rowOff + x] = (gx + gy) > 12f
                }
            }
        }
    }

    private data class FusionAlignmentResult(
        val globalDx: Int,
        val globalDy: Int,
        val tileShifts: IntArray?,
        val tileCols: Int,
        val tileRows: Int,
        val confidence: Float,
    )

    private fun alignFrameForFusion(
        otherPixels: IntArray,
        otherExposureBiasEv: Float,
        refLumaPlane: FloatArray,
        refThumb: FloatArray,
        refMeanLuma: Float,
        width: Int,
        height: Int,
        coarseW: Int,
        coarseH: Int,
        config: HdrPipelineConfig,
    ): FusionAlignmentResult? {
        val isBrightBracket = otherExposureBiasEv > 0.35f
        val isDarkBracket = otherExposureBiasEv < -0.35f
        val isExposureBracket = isBrightBracket || isDarkBracket
        val tileCols = 8
        val tileRows = 6

        val otherThumb = createLumaThumbnail(otherPixels, width, height, coarseW, coarseH)
        val otherMeanLuma = meanOfArray(otherThumb).coerceAtLeast(8f)
        val exposureGain = if (isExposureBracket) {
            2f.pow(-otherExposureBiasEv / 2.2f).coerceIn(0.25f, 4.0f)
        } else {
            (refMeanLuma / otherMeanLuma).coerceIn(0.35f, 2.85f)
        }

        val (globalDx, globalDy, conf) = if (config.subPixelAlignment) {
            val (coarseDx, coarseDy) = estimateCoarseShift(
                refThumb = refThumb,
                targetThumb = otherThumb,
                tw = coarseW,
                th = coarseH,
                fullW = width,
                fullH = height,
                exposureGain = exposureGain,
                skipClippedRef = isDarkBracket,
                skipClippedOther = isBrightBracket,
            )
            refineGlobalShiftOnePx(
                refLumaPlane = refLumaPlane,
                otherPixels = otherPixels,
                width = width,
                height = height,
                initDx = coarseDx,
                initDy = coarseDy,
                exposureGain = exposureGain,
                skipClippedRef = isDarkBracket,
                skipClippedOther = isBrightBracket,
            )
        } else {
            Triple(0, 0, 0.96f)
        }

        val minConf = if (isExposureBracket) 0.10f else 0.56f
        if (conf < minConf) return null

        val tileShifts = if (config.subPixelAlignment && width >= 128 && height >= 96) {
            refineLocalTileShifts(
                refLumaPlane = refLumaPlane,
                otherPixels = otherPixels,
                width = width,
                height = height,
                globalDx = globalDx,
                globalDy = globalDy,
                tileCols = tileCols,
                tileRows = tileRows,
                exposureGain = exposureGain,
                skipClippedRef = isDarkBracket,
                skipClippedOther = isBrightBracket,
            )
        } else null

        return FusionAlignmentResult(
            globalDx = globalDx,
            globalDy = globalDy,
            tileShifts = tileShifts,
            tileCols = tileCols,
            tileRows = tileRows,
            confidence = conf,
        )
    }

    /**
     * Streaming, memory-bounded Multi-Scale Laplacian Pyramid Exposure Fusion accumulator.
     * Level 0 uses compact ShortArrays (12-bit signed Laplacian + unsigned 16-bit weight) so full
     * 12.5MP fusion stays well within heap limits while eliminating cutout seams and bright halos.
     */
    private inner class PyramidExposureFusion(
        private val width: Int,
        private val height: Int,
    ) {
        private val lw: IntArray
        private val lh: IntArray
        private val levels: Int

        private val accR0: ShortArray
        private val accG0: ShortArray
        private val accB0: ShortArray
        private val accW0: ShortArray

        private val accR: Array<FloatArray>
        private val accG: Array<FloatArray>
        private val accB: Array<FloatArray>
        private val accW: Array<FloatArray>

        private val wellExposedLut: FloatArray

        init {
            val wList = ArrayList<Int>(6)
            val hList = ArrayList<Int>(6)
            var cw = width
            var ch = height
            wList.add(cw)
            hList.add(ch)
            while (wList.size < 6 && min(cw, ch) >= 12) {
                cw = (cw + 1) / 2
                ch = (ch + 1) / 2
                wList.add(cw)
                hList.add(ch)
            }
            levels = wList.size
            lw = IntArray(levels) { wList[it] }
            lh = IntArray(levels) { hList[it] }

            val total0 = width * height
            accR0 = ShortArray(total0)
            accG0 = ShortArray(total0)
            accB0 = ShortArray(total0)
            accW0 = ShortArray(total0)

            accR = Array(levels) { k -> if (k == 0) FloatArray(0) else FloatArray(lw[k] * lh[k]) }
            accG = Array(levels) { k -> if (k == 0) FloatArray(0) else FloatArray(lw[k] * lh[k]) }
            accB = Array(levels) { k -> if (k == 0) FloatArray(0) else FloatArray(lw[k] * lh[k]) }
            accW = Array(levels) { k -> if (k == 0) FloatArray(0) else FloatArray(lw[k] * lh[k]) }

            // Mid-tone well-exposedness curve with aggressive highlight-clip & shadow-crush suppression
            wellExposedLut = FloatArray(256) { idx ->
                val l = idx / 255f
                val d = l - 0.48f
                var e = exp(-(d * d) / (2f * 0.22f * 0.22f))
                if (l > 0.90f) {
                    // Drive clipped white pixels (248..255) near zero so -EV brackets replace them 99%+
                    e *= (1f - smoothstep(0.90f, 0.99f, l) * 0.996f)
                }
                if (l < 0.07f) {
                    e *= (0.04f + 0.96f * smoothstep(0.01f, 0.07f, l))
                }
                e.coerceIn(0.0005f, 1.0f)
            }
        }

        suspend fun accumulateFrame(
            pixels: IntArray,
            exposureBiasEv: Float,
            isReference: Boolean,
            refLumaPlane: FloatArray,
            refIsEdge: BooleanArray,
            faceBounds: IntArray,
            globalDx: Int,
            globalDy: Int,
            tileShifts: IntArray?,
            tileCols: Int,
            tileRows: Int,
        ) {
            val isDarkBracket = !isReference && exposureBiasEv < -0.35f
            val isBrightBracket = !isReference && exposureBiasEv > 0.35f
            val hasFaces = faceBounds.isNotEmpty()
            val maxBrightLiftScale = if (isBrightBracket) 2f.pow(exposureBiasEv / 1.05f) else 1f

            // Helper to sample the aligned RGB pixel at (x, y)
            fun alignedPixel(x: Int, y: Int, dx: Int, dy: Int): Int {
                val sx = (x + dx).coerceIn(0, width - 1)
                val sy = (y + dy).coerceIn(0, height - 1)
                return pixels[sy * width + sx]
            }

            fun computeWeight(x: Int, y: Int, idx: Int, r: Float, g: Float, b: Float): Float {
                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                val lumaIdx = luma.toInt().coerceIn(0, 255)
                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                val sat = if (maxC > 4f) (maxC - minC) / maxC else 0f
                val baseWell = wellExposedLut[lumaIdx]

                if (isReference) {
                    return (baseWell * (1f + 0.28f * sat)).coerceIn(0.0005f, 1.0f)
                }

                val refL = refLumaPlane[idx] * (1f / 255f)
                val otherL = luma * (1f / 255f)
                val faceW = if (hasFaces) faceMaskWeight(x, y, faceBounds) else 0f

                if (isDarkBracket) {
                    // Only recover bright / clipped regions; never darken well-exposed faces or room shadows
                    val hlGate = smoothstep(0.48f, 0.80f, refL)
                    if (hlGate <= 0.001f) return 0f
                    // Reject strong motion ghosts where dark bracket is unexpectedly brighter than 0 EV
                    val ghostGuard = if (otherL > refL + 0.12f) 0.04f else 1f
                    // Protect already well-exposed faces from head/blink ghosting during dark brackets
                    val faceGuard = if (faceW > 0.001f && refL < 0.84f) {
                        1f - faceW * 0.92f
                    } else 1f
                    return (baseWell * (1f + 0.36f * sat) * hlGate * ghostGuard * faceGuard).coerceIn(0f, 1.0f)
                }

                if (isBrightBracket) {
                    // Only lift shadowed / dark-face regions; never wash out bright windows
                    val shGate = 1f - smoothstep(0.32f, 0.62f, refL)
                    if (shGate <= 0.001f) return 0f
                    val headroom = if (faceW > 0.25f) 0.22f else 0.28f
                    val expectedMax = (refL * maxBrightLiftScale + headroom).coerceAtMost(0.96f)
                    if (otherL < refL * 0.80f || otherL > expectedMax) return 0f
                    val edgeGuard = if (faceW > 0.25f && refIsEdge[idx]) 0.72f else 1f
                    return (baseWell * (1f + 0.24f * sat) * shGate * edgeGuard).coerceIn(0f, 1.0f)
                }

                // Additional 0 EV frame in a 4–5 frame burst: ghost-checked temporal SNR fill
                val diff = abs(otherL - refL)
                val limit = if (faceW > 0.25f || refIsEdge[idx]) 0.05f else 0.11f
                if (diff > limit) return 0f
                val tW = 1f - (diff / limit)
                return (baseWell * tW * 0.65f).coerceIn(0f, 1.0f)
            }

            if (levels == 1) {
                forEachRowChunk(height) { yStart, yEnd ->
                    for (y in yStart until yEnd) {
                        val rowOff = y * width
                        val tyBase = ((y * tileRows) / height).coerceIn(0, tileRows - 1) * tileCols
                        for (tx in 0 until tileCols) {
                            val x0 = (tx * width) / tileCols
                            val x1 = ((tx + 1) * width) / tileCols
                            val (dx, dy) = if (tileShifts != null) {
                                val tIdx = (tyBase + tx) * 2
                                tileShifts[tIdx] to tileShifts[tIdx + 1]
                            } else globalDx to globalDy
                            for (x in x0 until x1) {
                                val i = rowOff + x
                                val p = if (isReference) pixels[i] else alignedPixel(x, y, dx, dy)
                                val r = ((p shr 16) and 0xFF).toFloat()
                                val g = ((p shr 8) and 0xFF).toFloat()
                                val b = (p and 0xFF).toFloat()
                                val w = computeWeight(x, y, i, r, g, b)
                                if (w > 0.0002f) {
                                    accR0[i] = (accR0[i].toInt() + (w * r * 16f).toInt()).coerceIn(-32767, 32767).toShort()
                                    accG0[i] = (accG0[i].toInt() + (w * g * 16f).toInt()).coerceIn(-32767, 32767).toShort()
                                    accB0[i] = (accB0[i].toInt() + (w * b * 16f).toInt()).coerceIn(-32767, 32767).toShort()
                                    val nw = ((accW0[i].toInt() and 0xFFFF) + (w * 4096f).toInt()).coerceIn(0, 65535)
                                    accW0[i] = nw.toShort()
                                }
                            }
                        }
                    }
                }
                return
            }

            // Pass 1 (Parallel): Downsample Level 0 -> Level 1 (RGB + W) directly from aligned pixels
            // using a 3x3 tent kernel centered at (2*x1, 2*y1) — zero full-res float allocations!
            val w1 = lw[1]
            val h1 = lh[1]
            val size1 = w1 * h1
            var curR = FloatArray(size1)
            var curG = FloatArray(size1)
            var curB = FloatArray(size1)
            var curW = FloatArray(size1)

            forEachRowChunk(h1) { y1Start, y1End ->
                for (y1 in y1Start until y1End) {
                    val cy = (y1 shl 1).coerceAtMost(height - 1)
                    val tyBase = ((cy * tileRows) / height).coerceIn(0, tileRows - 1) * tileCols
                    val dstRow = y1 * w1
                    for (x1 in 0 until w1) {
                        val cx = (x1 shl 1).coerceAtMost(width - 1)
                        val tx = ((cx * tileCols) / width).coerceIn(0, tileCols - 1)
                        val (dx, dy) = if (tileShifts != null) {
                            val tIdx = (tyBase + tx) * 2
                            tileShifts[tIdx] to tileShifts[tIdx + 1]
                        } else globalDx to globalDy

                        var sr = 0f
                        var sg = 0f
                        var sb = 0f
                        var sw = 0f
                        var kSum = 0f
                        for (oy in -1..1) {
                            val ky = if (oy == 0) 2f else 1f
                            val py = (cy + oy).coerceIn(0, height - 1)
                            val pRowOff = py * width
                            for (ox in -1..1) {
                                val kx = if (ox == 0) 2f else 1f
                                val k = kx * ky
                                val px = (cx + ox).coerceIn(0, width - 1)
                                val pIdx = pRowOff + px
                                val p = if (isReference) pixels[pIdx] else alignedPixel(px, py, dx, dy)
                                val r = ((p shr 16) and 0xFF).toFloat()
                                val g = ((p shr 8) and 0xFF).toFloat()
                                val b = (p and 0xFF).toFloat()
                                val wt = computeWeight(px, py, pIdx, r, g, b)
                                sr += r * k
                                sg += g * k
                                sb += b * k
                                sw += wt * k
                                kSum += k
                            }
                        }
                        val invK = 1f / kSum
                        val di = dstRow + x1
                        curR[di] = sr * invK
                        curG[di] = sg * invK
                        curB[di] = sb * invK
                        curW[di] = sw * invK
                    }
                }
            }

            // Pass 2 (Parallel): Compute Level 0 Laplacian = I0 - expand(I1) on the fly and accumulate into ShortArrays
            forEachRowChunk(height) { yStart, yEnd ->
                for (y in yStart until yEnd) {
                    val rowOff = y * width
                    val tyBase = ((y * tileRows) / height).coerceIn(0, tileRows - 1) * tileCols
                    val y1a = (y shr 1).coerceAtMost(h1 - 1)
                    val y1b = (y1a + 1).coerceAtMost(h1 - 1)
                    val wy = if ((y and 1) == 0) 0f else 0.5f
                    val r1a = y1a * w1
                    val r1b = y1b * w1

                    for (tx in 0 until tileCols) {
                        val xStart = (tx * width) / tileCols
                        val xEnd = ((tx + 1) * width) / tileCols
                        val (dx, dy) = if (tileShifts != null) {
                            val tIdx = (tyBase + tx) * 2
                            tileShifts[tIdx] to tileShifts[tIdx + 1]
                        } else globalDx to globalDy

                        for (x in xStart until xEnd) {
                            val i = rowOff + x
                            val p = if (isReference) pixels[i] else alignedPixel(x, y, dx, dy)
                            val r = ((p shr 16) and 0xFF).toFloat()
                            val g = ((p shr 8) and 0xFF).toFloat()
                            val b = (p and 0xFF).toFloat()
                            val w = computeWeight(x, y, i, r, g, b)
                            if (w <= 0.0002f) continue

                            val x1a = (x shr 1).coerceAtMost(w1 - 1)
                            val x1b = (x1a + 1).coerceAtMost(w1 - 1)
                            val wx = if ((x and 1) == 0) 0f else 0.5f

                            val upR = bilinearSample(curR, r1a, r1b, x1a, x1b, wx, wy)
                            val upG = bilinearSample(curG, r1a, r1b, x1a, x1b, wx, wy)
                            val upB = bilinearSample(curB, r1a, r1b, x1a, x1b, wx, wy)

                            val lapR = r - upR
                            val lapG = g - upG
                            val lapB = b - upB

                            accR0[i] = (accR0[i].toInt() + (w * lapR * 16f).toInt()).coerceIn(-32767, 32767).toShort()
                            accG0[i] = (accG0[i].toInt() + (w * lapG * 16f).toInt()).coerceIn(-32767, 32767).toShort()
                            accB0[i] = (accB0[i].toInt() + (w * lapB * 16f).toInt()).coerceIn(-32767, 32767).toShort()
                            val nw = ((accW0[i].toInt() and 0xFFFF) + (w * 4096f).toInt()).coerceIn(0, 65535)
                            accW0[i] = nw.toShort()
                        }
                    }
                }
            }

            // Pass 3: Build & accumulate Levels 1 .. levels - 1 (<= 25% resolution, very fast)
            for (k in 1 until levels - 1) {
                val sw = lw[k]
                val sh = lh[k]
                val dw = lw[k + 1]
                val dh = lh[k + 1]
                val nextR = reducePlaneGaussian(curR, sw, sh, dw, dh)
                val nextG = reducePlaneGaussian(curG, sw, sh, dw, dh)
                val nextB = reducePlaneGaussian(curB, sw, sh, dw, dh)
                val nextW = reducePlaneGaussian(curW, sw, sh, dw, dh)

                val dstAccR = accR[k]
                val dstAccG = accG[k]
                val dstAccB = accB[k]
                val dstAccW = accW[k]

                for (y in 0 until sh) {
                    val rowOff = y * sw
                    val ya = (y shr 1).coerceAtMost(dh - 1)
                    val yb = (ya + 1).coerceAtMost(dh - 1)
                    val wy = if ((y and 1) == 0) 0f else 0.5f
                    val ra = ya * dw
                    val rb = yb * dw
                    for (x in 0 until sw) {
                        val i = rowOff + x
                        val w = curW[i]
                        if (w <= 0.0001f) continue
                        val xa = (x shr 1).coerceAtMost(dw - 1)
                        val xb = (xa + 1).coerceAtMost(dw - 1)
                        val wx = if ((x and 1) == 0) 0f else 0.5f
                        val upR = bilinearSample(nextR, ra, rb, xa, xb, wx, wy)
                        val upG = bilinearSample(nextG, ra, rb, xa, xb, wx, wy)
                        val upB = bilinearSample(nextB, ra, rb, xa, xb, wx, wy)
                        dstAccR[i] += w * (curR[i] - upR)
                        dstAccG[i] += w * (curG[i] - upG)
                        dstAccB[i] += w * (curB[i] - upB)
                        dstAccW[i] += w
                    }
                }

                curR = nextR
                curG = nextG
                curB = nextB
                curW = nextW
            }

            // Coarsest Gaussian base level
            val top = levels - 1
            val topAccR = accR[top]
            val topAccG = accG[top]
            val topAccB = accB[top]
            val topAccW = accW[top]
            for (i in curR.indices) {
                val w = curW[i]
                if (w > 0.0001f) {
                    topAccR[i] += w * curR[i]
                    topAccG[i] += w * curG[i]
                    topAccB[i] += w * curB[i]
                    topAccW[i] += w
                }
            }
        }

        suspend fun collapseToPixels(outPixels: IntArray) {
            if (levels == 1) {
                forEachRowChunk(height) { yStart, yEnd ->
                    val s = yStart * width
                    val e = yEnd * width
                    for (i in s until e) {
                        val wSum = ((accW0[i].toInt() and 0xFFFF) / 4096f).coerceAtLeast(1e-4f)
                        val r = ((accR0[i].toFloat() / 16f) / wSum).toInt().coerceIn(0, 255)
                        val g = ((accG0[i].toFloat() / 16f) / wSum).toInt().coerceIn(0, 255)
                        val b = ((accB0[i].toFloat() / 16f) / wSum).toInt().coerceIn(0, 255)
                        outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                return
            }

            val top = levels - 1
            var reconR = FloatArray(lw[top] * lh[top]) { i ->
                accR[top][i] / accW[top][i].coerceAtLeast(1e-4f)
            }
            var reconG = FloatArray(lw[top] * lh[top]) { i ->
                accG[top][i] / accW[top][i].coerceAtLeast(1e-4f)
            }
            var reconB = FloatArray(lw[top] * lh[top]) { i ->
                accB[top][i] / accW[top][i].coerceAtLeast(1e-4f)
            }

            for (k in (levels - 2) downTo 1) {
                val dw = lw[k]
                val dh = lh[k]
                val sw = lw[k + 1]
                val sh = lh[k + 1]
                val nextR = FloatArray(dw * dh)
                val nextG = FloatArray(dw * dh)
                val nextB = FloatArray(dw * dh)
                val lapR = accR[k]
                val lapG = accG[k]
                val lapB = accB[k]
                val wArr = accW[k]

                for (y in 0 until dh) {
                    val rowOff = y * dw
                    val ya = (y shr 1).coerceAtMost(sh - 1)
                    val yb = (ya + 1).coerceAtMost(sh - 1)
                    val wy = if ((y and 1) == 0) 0f else 0.5f
                    val ra = ya * sw
                    val rb = yb * sw
                    for (x in 0 until dw) {
                        val i = rowOff + x
                        val xa = (x shr 1).coerceAtMost(sw - 1)
                        val xb = (xa + 1).coerceAtMost(sw - 1)
                        val wx = if ((x and 1) == 0) 0f else 0.5f
                        val invW = 1f / wArr[i].coerceAtLeast(1e-4f)
                        nextR[i] = bilinearSample(reconR, ra, rb, xa, xb, wx, wy) + lapR[i] * invW
                        nextG[i] = bilinearSample(reconG, ra, rb, xa, xb, wx, wy) + lapG[i] * invW
                        nextB[i] = bilinearSample(reconB, ra, rb, xa, xb, wx, wy) + lapB[i] * invW
                    }
                }
                reconR = nextR
                reconG = nextG
                reconB = nextB
            }

            // Collapse Level 1 + Level 0 Laplacian directly into output ARGB pixels in parallel
            val w1 = lw[1]
            val h1 = lh[1]
            val finalBaseR = reconR
            val finalBaseG = reconG
            val finalBaseB = reconB
            forEachRowChunk(height) { yStart, yEnd ->
                for (y in yStart until yEnd) {
                    val rowOff = y * width
                    val ya = (y shr 1).coerceAtMost(h1 - 1)
                    val yb = (ya + 1).coerceAtMost(h1 - 1)
                    val wy = if ((y and 1) == 0) 0f else 0.5f
                    val ra = ya * w1
                    val rb = yb * w1
                    for (x in 0 until width) {
                        val i = rowOff + x
                        val xa = (x shr 1).coerceAtMost(w1 - 1)
                        val xb = (xa + 1).coerceAtMost(w1 - 1)
                        val wx = if ((x and 1) == 0) 0f else 0.5f

                        val upR = bilinearSample(finalBaseR, ra, rb, xa, xb, wx, wy)
                        val upG = bilinearSample(finalBaseG, ra, rb, xa, xb, wx, wy)
                        val upB = bilinearSample(finalBaseB, ra, rb, xa, xb, wx, wy)

                        val wSum = ((accW0[i].toInt() and 0xFFFF) / 4096f).coerceAtLeast(1e-4f)
                        val invW = (1f / 16f) / wSum
                        val r = (upR + accR0[i].toFloat() * invW).toInt().coerceIn(0, 255)
                        val g = (upG + accG0[i].toFloat() * invW).toInt().coerceIn(0, 255)
                        val b = (upB + accB0[i].toFloat() * invW).toInt().coerceIn(0, 255)
                        outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
        }

        private fun bilinearSample(
            plane: FloatArray,
            rowA: Int,
            rowB: Int,
            xA: Int,
            xB: Int,
            wx: Float,
            wy: Float,
        ): Float {
            val v00 = plane[rowA + xA]
            val v10 = plane[rowA + xB]
            val v01 = plane[rowB + xA]
            val v11 = plane[rowB + xB]
            val top = v00 + (v10 - v00) * wx
            val bot = v01 + (v11 - v01) * wx
            return top + (bot - top) * wy
        }

        private fun reducePlaneGaussian(
            src: FloatArray,
            sw: Int,
            sh: Int,
            dw: Int,
            dh: Int,
        ): FloatArray {
            // Separable 5-tap [1, 4, 6, 4, 1]/16 horizontal pass with 2x decimation
            val tmp = FloatArray(dw * sh)
            for (y in 0 until sh) {
                val sRow = y * sw
                val tRow = y * dw
                for (dx in 0 until dw) {
                    val cx = dx shl 1
                    val c0 = src[sRow + (cx - 2).coerceIn(0, sw - 1)]
                    val c1 = src[sRow + (cx - 1).coerceIn(0, sw - 1)]
                    val c2 = src[sRow + cx.coerceIn(0, sw - 1)]
                    val c3 = src[sRow + (cx + 1).coerceIn(0, sw - 1)]
                    val c4 = src[sRow + (cx + 2).coerceIn(0, sw - 1)]
                    tmp[tRow + dx] = (c0 + 4f * c1 + 6f * c2 + 4f * c3 + c4) * (1f / 16f)
                }
            }
            // Vertical 5-tap pass with 2x decimation
            val dst = FloatArray(dw * dh)
            for (dy in 0 until dh) {
                val cy = dy shl 1
                val r0 = (cy - 2).coerceIn(0, sh - 1) * dw
                val r1 = (cy - 1).coerceIn(0, sh - 1) * dw
                val r2 = cy.coerceIn(0, sh - 1) * dw
                val r3 = (cy + 1).coerceIn(0, sh - 1) * dw
                val r4 = (cy + 2).coerceIn(0, sh - 1) * dw
                val dRow = dy * dw
                for (dx in 0 until dw) {
                    dst[dRow + dx] = (tmp[r0 + dx] + 4f * tmp[r1 + dx] + 6f * tmp[r2 + dx] +
                        4f * tmp[r3 + dx] + tmp[r4 + dx]) * (1f / 16f)
                }
            }
            return dst
        }
    }

    /**
     * Streaming multi-frame temporal merge for large Night Sight bursts (>4 frames).
     * Decodes and merges each secondary JPEG frame one by one to keep peak heap bounded.
     */
    private suspend fun mergeRawFramesStreaming(
        mergedPixels: IntArray,
        rawFrames: List<RawCapturedFrame>,
        thumbSharpnesses: FloatArray,
        refIndex: Int,
        refSharpness: Float,
        width: Int,
        height: Int,
        config: HdrPipelineConfig,
        nightSightEnabled: Boolean,
        detectedFaces: List<DetectedFace> = emptyList(),
    ): Pair<Float, Int> {
        val totalPixels = width * height
        val accR = FloatArray(totalPixels)
        val accG = FloatArray(totalPixels)
        val accB = FloatArray(totalPixels)
        val accW = FloatArray(totalPixels)
        val refLumaPlane = FloatArray(totalPixels)
        val refIsEdge = BooleanArray(totalPixels)
        val faceBounds = buildFacePixelBounds(detectedFaces, width, height, padFraction = 0.12f)

        initAccumulators(mergedPixels, width, height, accR, accG, accB, accW, refLumaPlane, refIsEdge)

        val coarseW = 160
        val coarseH = 120
        val refThumb = createLumaThumbnailFromPlane(refLumaPlane, width, height, coarseW, coarseH)
        val refMeanLuma = meanOfArray(refThumb).coerceAtLeast(8f)

        val flatSigma = if (nightSightEnabled) 26f + config.temporalDenoise * 14f else 16f + config.temporalDenoise * 10f
        val edgeSigma = if (nightSightEnabled) 11f else 6f
        val flatWeightLut = buildExpDiffLut(flatSigma)
        val edgeWeightLut = buildExpDiffLut(edgeSigma)
        val sharpnessMinRatio = if (nightSightEnabled) 0.46f else 0.62f
        val minConfidence = if (nightSightEnabled) 0.45f else 0.56f

        var confSum = 0f
        var alignedCount = 0
        var effectiveFramesMerged = 1

        for (fIdx in rawFrames.indices) {
            if (fIdx == refIndex) continue
            val raw = rawFrames[fIdx]
            val isBracket = abs(raw.exposureBiasEv) >= 0.35f
            if (!isBracket && refSharpness > 0.0005f && thumbSharpnesses[fIdx] < refSharpness * sharpnessMinRatio) {
                continue
            }

            val ownsBmp = raw !is RawCapturedFrame.DecodedBitmap
            val bmp = CameraController.decodeRawFrame(raw, maxLongEdge = MAX_PROCESS_LONG_EDGE)
            var otherPixels: IntArray? = null
            try {
                val scaled = downscaleIfNeeded(bmp, MAX_PROCESS_LONG_EDGE)
                try {
                    if (scaled.width == width && scaled.height == height) {
                        otherPixels = IntArray(totalPixels).also {
                            scaled.getPixels(it, 0, width, 0, 0, width, height)
                        }
                    }
                } finally {
                    if (scaled !== bmp && !scaled.isRecycled) scaled.recycle()
                }
            } finally {
                if (ownsBmp && !bmp.isRecycled) bmp.recycle()
            }

            val pixels = otherPixels ?: continue
            val conf = alignAndAccumulateFrame(
                otherPixels = pixels,
                otherExposureBiasEv = raw.exposureBiasEv,
                refLumaPlane = refLumaPlane,
                refIsEdge = refIsEdge,
                refThumb = refThumb,
                refMeanLuma = refMeanLuma,
                accR = accR,
                accG = accG,
                accB = accB,
                accW = accW,
                flatWeightLut = flatWeightLut,
                edgeWeightLut = edgeWeightLut,
                width = width,
                height = height,
                coarseW = coarseW,
                coarseH = coarseH,
                minConfidence = if (isBracket) 0.40f else minConfidence,
                config = config,
                nightSightEnabled = nightSightEnabled,
                faceBounds = faceBounds,
            )
            if (conf != null) {
                confSum += conf
                alignedCount++
                effectiveFramesMerged++
            }
        }

        val alignmentConfidence = if (alignedCount > 0) {
            (confSum / alignedCount).coerceIn(0.45f, 0.99f)
        } else {
            1.0f
        }

        normalizeAccumulators(mergedPixels, accR, accG, accB, accW, width, height)
        return alignmentConfidence to effectiveFramesMerged
    }

    /**
     * Scoped temporal alignment & merge pass. Isolating the 6 large accumulation arrays inside
     * this method allows the GC to immediately reclaim heap before tone mapping & sharpening run.
     */
    private suspend fun mergeFramesInPlace(
        mergedPixels: IntArray,
        extracted: MutableList<ExtractedFrame>,
        refIndex: Int,
        refSharpness: Float,
        width: Int,
        height: Int,
        config: HdrPipelineConfig,
        nightSightEnabled: Boolean = false,
        detectedFaces: List<DetectedFace> = emptyList(),
    ): Pair<Float, Int> {
        val totalPixels = width * height
        val accR = FloatArray(totalPixels)
        val accG = FloatArray(totalPixels)
        val accB = FloatArray(totalPixels)
        val accW = FloatArray(totalPixels)
        val refLumaPlane = FloatArray(totalPixels)
        val refIsEdge = BooleanArray(totalPixels)
        val faceBounds = buildFacePixelBounds(detectedFaces, width, height, padFraction = 0.12f)

        initAccumulators(mergedPixels, width, height, accR, accG, accB, accW, refLumaPlane, refIsEdge)

        val coarseW = 160
        val coarseH = 120
        val refThumb = createLumaThumbnailFromPlane(refLumaPlane, width, height, coarseW, coarseH)
        val refMeanLuma = meanOfArray(refThumb).coerceAtLeast(8f)

        val flatSigma = if (nightSightEnabled) 26f + config.temporalDenoise * 14f else 16f + config.temporalDenoise * 10f
        val edgeSigma = if (nightSightEnabled) 11f else 6f
        val flatWeightLut = buildExpDiffLut(flatSigma)
        val edgeWeightLut = buildExpDiffLut(edgeSigma)
        val sharpnessMinRatio = if (nightSightEnabled) 0.46f else 0.62f
        val minConfidence = if (nightSightEnabled) 0.45f else 0.56f

        var confSum = 0f
        var alignedCount = 0
        var effectiveFramesMerged = 1

        for (fIdx in extracted.indices) {
            if (fIdx == refIndex) continue
            val frame = extracted[fIdx]
            val otherPixels = frame.pixels
            // Free secondary IntArray slot as soon as we grab it locally
            frame.pixels = null
            if (otherPixels == null || frame.width != width || frame.height != height) continue

            val isBracket = abs(frame.exposureBiasEv) >= 0.35f
            // Reject secondary frames that have noticeable motion or focus blur compared to reference
            if (!isBracket && refSharpness > 0.0005f && frame.sharpness < refSharpness * sharpnessMinRatio) {
                continue
            }

            val conf = alignAndAccumulateFrame(
                otherPixels = otherPixels,
                otherExposureBiasEv = frame.exposureBiasEv,
                refLumaPlane = refLumaPlane,
                refIsEdge = refIsEdge,
                refThumb = refThumb,
                refMeanLuma = refMeanLuma,
                accR = accR,
                accG = accG,
                accB = accB,
                accW = accW,
                flatWeightLut = flatWeightLut,
                edgeWeightLut = edgeWeightLut,
                width = width,
                height = height,
                coarseW = coarseW,
                coarseH = coarseH,
                minConfidence = if (isBracket) 0.40f else minConfidence,
                config = config,
                nightSightEnabled = nightSightEnabled,
                faceBounds = faceBounds,
            )
            if (conf != null) {
                confSum += conf
                alignedCount++
                effectiveFramesMerged++
            }
        }

        val alignmentConfidence = if (alignedCount > 0) {
            (confSum / alignedCount).coerceIn(0.45f, 0.99f)
        } else {
            1.0f
        }

        normalizeAccumulators(mergedPixels, accR, accG, accB, accW, width, height)
        return alignmentConfidence to effectiveFramesMerged
    }

    private suspend fun initAccumulators(
        mergedPixels: IntArray,
        width: Int,
        height: Int,
        accR: FloatArray,
        accG: FloatArray,
        accB: FloatArray,
        accW: FloatArray,
        refLumaPlane: FloatArray,
        refIsEdge: BooleanArray,
    ) {
        forEachRowChunk(height) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val rowOff = y * width
                for (x in 0 until width) {
                    val i = rowOff + x
                    val p = mergedPixels[i]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    accR[i] = r.toFloat()
                    accG[i] = g.toFloat()
                    accB[i] = b.toFloat()
                    accW[i] = 1.0f
                    refLumaPlane[i] = 0.299f * r + 0.587f * g + 0.114f * b
                }
            }
        }

        // Precompute fine-detail & edge mask (lower threshold 12f protects subtle skin/hair/foliage texture)
        forEachRowChunk(height) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val rowOff = y * width
                val yPrev = (y - 1).coerceAtLeast(0) * width
                val yNext = (y + 1).coerceAtMost(height - 1) * width
                for (x in 0 until width) {
                    val xPrev = (x - 1).coerceAtLeast(0)
                    val xNext = (x + 1).coerceAtMost(width - 1)
                    val gx = abs(refLumaPlane[rowOff + xNext] - refLumaPlane[rowOff + xPrev])
                    val gy = abs(refLumaPlane[yNext + x] - refLumaPlane[yPrev + x])
                    refIsEdge[rowOff + x] = (gx + gy) > 12f
                }
            }
        }
    }

    private suspend fun alignAndAccumulateFrame(
        otherPixels: IntArray,
        otherExposureBiasEv: Float = 0f,
        refLumaPlane: FloatArray,
        refIsEdge: BooleanArray,
        refThumb: FloatArray,
        refMeanLuma: Float,
        accR: FloatArray,
        accG: FloatArray,
        accB: FloatArray,
        accW: FloatArray,
        flatWeightLut: FloatArray,
        edgeWeightLut: FloatArray,
        width: Int,
        height: Int,
        coarseW: Int,
        coarseH: Int,
        minConfidence: Float,
        config: HdrPipelineConfig,
        nightSightEnabled: Boolean,
        faceBounds: IntArray = IntArray(0),
    ): Float? {
        val isBrightShadowBracket = otherExposureBiasEv > 0.35f
        val isDarkHighlightBracket = otherExposureBiasEv < -0.35f
        val isExposureBracket = isBrightShadowBracket || isDarkHighlightBracket
        val hasFaces = faceBounds.isNotEmpty()

        val tileCols = 8
        val tileRows = 6
        val otherThumb = createLumaThumbnail(otherPixels, width, height, coarseW, coarseH)
        val otherMeanLuma = meanOfArray(otherThumb).coerceAtLeast(8f)
        val exposureGain = if (isExposureBracket) {
            // Use physical EV-derived gamma-space gain so clipped skies/crushed shadows do not skew alignment
            2f.pow(-otherExposureBiasEv / 2.2f).coerceIn(0.35f, 2.85f)
        } else {
            (refMeanLuma / otherMeanLuma).coerceIn(0.35f, 2.85f)
        }

        val (globalDx, globalDy, conf) = if (config.subPixelAlignment) {
            val (coarseDx, coarseDy) = estimateCoarseShift(
                refThumb = refThumb,
                targetThumb = otherThumb,
                tw = coarseW,
                th = coarseH,
                fullW = width,
                fullH = height,
                exposureGain = exposureGain,
            )
            refineGlobalShiftOnePx(
                refLumaPlane = refLumaPlane,
                otherPixels = otherPixels,
                width = width,
                height = height,
                initDx = coarseDx,
                initDy = coarseDy,
                exposureGain = exposureGain,
            )
        } else {
            Triple(0, 0, 0.96f)
        }

        // Exposure brackets intentionally differ strongly in luminance on clipped regions; accept down to 0.10
        val effectiveMinConf = if (isExposureBracket) 0.10f else minConfidence
        if (conf < effectiveMinConf) {
            return null
        }

        val tileShifts = if (config.subPixelAlignment && width >= 128 && height >= 96) {
            refineLocalTileShifts(
                refLumaPlane = refLumaPlane,
                otherPixels = otherPixels,
                width = width,
                height = height,
                globalDx = globalDx,
                globalDy = globalDy,
                tileCols = tileCols,
                tileRows = tileRows,
                exposureGain = exposureGain,
            )
        } else {
            null
        }

        val edgeRejectDiff = if (nightSightEnabled) 12f else 6f
        val faceEdgeRejectDiff = if (nightSightEnabled) 7.5f else 3.6f
        val maxBracketLiftScale = if (isBrightShadowBracket) 2f.pow(otherExposureBiasEv / 1.1f) else 1f

        forEachRowChunk(height) { yStart, yEnd ->
            for (y in yStart until yEnd) {
                val rowOff = y * width
                val ty = ((y * tileRows) / height).coerceIn(0, tileRows - 1)
                val tyBase = ty * tileCols

                for (tx in 0 until tileCols) {
                    val xStart = (tx * width) / tileCols
                    val xEnd = ((tx + 1) * width) / tileCols
                    val (dx, dy) = if (tileShifts != null) {
                        val tIdx = (tyBase + tx) * 2
                        tileShifts[tIdx] to tileShifts[tIdx + 1]
                    } else {
                        globalDx to globalDy
                    }
                    val sy = (y + dy).coerceIn(0, height - 1)
                    val sRowOff = sy * width

                    for (x in xStart until xEnd) {
                        val i = rowOff + x
                        val sx = (x + dx).coerceIn(0, width - 1)
                        val sp = otherPixels[sRowOff + sx]

                        val r = ((sp shr 16) and 0xFF).toFloat()
                        val g = ((sp shr 8) and 0xFF).toFloat()
                        val b = (sp and 0xFF).toFloat()

                        val refLuma = refLumaPlane[i]
                        val rawOtherLuma = 0.299f * r + 0.587f * g + 0.114f * b
                        val inFace = hasFaces && isInsideFaceBounds(x, y, faceBounds)

                        if (isBrightShadowBracket) {
                            // Exposure fusion for backlit subjects: inject clean real photons from the +EV
                            // frame directly into shadowed subject pixels without blowing out highlights.
                            if (refLuma < 125f && rawOtherLuma in 18f..242f) {
                                val shadowFactor = ((125f - refLuma) / 105f).coerceIn(0f, 1f)
                                val wellExposedFactor = if (rawOtherLuma > 215f) {
                                    ((242f - rawOtherLuma) / 27f).coerceIn(0f, 1f)
                                } else 1f
                                // Tighter ghost rejection inside detected faces so blinking/expression changes
                                // never smear eyes or lips during the backlit +EV bracket capture.
                                val headroom = if (inFace) 52f else 68f
                                val expectedMaxLuma = (refLuma * maxBracketLiftScale + headroom).coerceAtMost(245f)
                                if (rawOtherLuma >= refLuma * 0.80f && rawOtherLuma <= expectedMaxLuma) {
                                    val edgeGuard = if (inFace && refIsEdge[i]) 0.55f else 1f
                                    val fuseW = (shadowFactor * shadowFactor * wellExposedFactor * 1.45f * edgeGuard)
                                    accR[i] += r * fuseW
                                    accG[i] += g * fuseW
                                    accB[i] += b * fuseW
                                    accW[i] += fuseW
                                }
                            }
                            continue
                        }

                        if (isDarkHighlightBracket) {
                            // Exposure fusion for clipped backlight/sky: recover real highlight texture from -EV frame
                            if (refLuma > 180f && rawOtherLuma in 36f..242f && rawOtherLuma <= refLuma * 1.05f) {
                                val hlFactor = ((refLuma - 180f) / 75f).coerceIn(0f, 1f)
                                val fuseW = hlFactor * hlFactor * 1.65f
                                accR[i] += r * fuseW
                                accG[i] += g * fuseW
                                accB[i] += b * fuseW
                                accW[i] += fuseW
                            }
                            continue
                        }

                        val normR = (r * exposureGain).coerceAtMost(255f)
                        val normG = (g * exposureGain).coerceAtMost(255f)
                        val normB = (b * exposureGain).coerceAtMost(255f)
                        val normOtherLuma = 0.299f * normR + 0.587f * normG + 0.114f * normB

                        val absDiff = abs(normOtherLuma - refLuma)
                        val diffIdx = absDiff.toInt().coerceIn(0, 255)
                        val isEdge = refIsEdge[i]
                        var w = if (isEdge) edgeWeightLut[diffIdx] else flatWeightLut[diffIdx]

                        if (refLuma > 232f && rawOtherLuma < 236f && exposureGain > 1.15f) {
                            if (w < 0.45f) w = 0.45f
                        } else if (isEdge) {
                            // Protect crisp micro-edges from sub-pixel misalignment blur; extra strict on faces
                            val rejectLimit = if (inFace) faceEdgeRejectDiff else edgeRejectDiff
                            if (absDiff > rejectLimit) {
                                w = 0f
                            } else if (absDiff > (if (inFace) 2.2f else 3.5f)) {
                                w *= if (inFace) 0.15f else 0.25f
                            }
                        } else if (inFace && absDiff > (if (nightSightEnabled) 18f else 11f)) {
                            // Ghost rejection on moving skin/eyelids/mouth inside detected face regions
                            w *= 0.20f
                        }

                        if (w > 0.001f) {
                            accR[i] += normR * w
                            accG[i] += normG * w
                            accB[i] += normB * w
                            accW[i] += w
                        }
                    }
                }
            }
        }
        return conf
    }

    private suspend fun normalizeAccumulators(
        mergedPixels: IntArray,
        accR: FloatArray,
        accG: FloatArray,
        accB: FloatArray,
        accW: FloatArray,
        width: Int,
        height: Int,
    ) {
        forEachRowChunk(height) { yStart, yEnd ->
            val idxStart = yStart * width
            val idxEnd = yEnd * width
            for (i in idxStart until idxEnd) {
                val invW = 1f / accW[i]
                val r = (accR[i] * invW).toInt().coerceIn(0, 255)
                val g = (accG[i] * invW).toInt().coerceIn(0, 255)
                val b = (accB[i] * invW).toInt().coerceIn(0, 255)
                mergedPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /**
     * 2-pass edge-preserving bilateral chroma-only denoise for Night Sight captures:
     * suppresses high-ISO green/magenta color noise across a 5x5 effective support in Cb/Cr
     * while keeping Y luminance 100% sharp and untouched.
     */
    private suspend fun denoiseChromaInPlace(
        pixels: IntArray,
        width: Int,
        height: Int,
    ) {
        val total = width * height
        val yPlane = FloatArray(total)
        var srcCb = FloatArray(total)
        var srcCr = FloatArray(total)
        var dstCb = FloatArray(total)
        var dstCr = FloatArray(total)

        forEachRowChunk(height) { yStart, yEnd ->
            val idxStart = yStart * width
            val idxEnd = yEnd * width
            for (i in idxStart until idxEnd) {
                val p = pixels[i]
                val r = ((p shr 16) and 0xFF).toFloat()
                val g = ((p shr 8) and 0xFF).toFloat()
                val b = (p and 0xFF).toFloat()
                val yVal = 0.299f * r + 0.587f * g + 0.114f * b
                yPlane[i] = yVal
                srcCb[i] = b - yVal
                srcCr[i] = r - yVal
            }
        }

        val lumaEdgeLut = buildExpDiffLut(sigma = 24f)
        for (stride in intArrayOf(1, 2)) {
            val inCb = srcCb
            val inCr = srcCr
            val outCb = dstCb
            val outCr = dstCr
            forEachRowChunk(height) { yStart, yEnd ->
                for (y in yStart until yEnd) {
                    val row = y * width
                    val rPrev = (y - stride).coerceAtLeast(0) * width
                    val rNext = (y + stride).coerceAtMost(height - 1) * width
                    for (x in 0 until width) {
                        val i = row + x
                        val cY = yPlane[i]
                        val xPrev = (x - stride).coerceAtLeast(0)
                        val xNext = (x + stride).coerceAtMost(width - 1)

                        var sumCb = inCb[i]
                        var sumCr = inCr[i]
                        var sumW = 1f

                        val n0 = rPrev + xPrev; val w0 = lumaEdgeLut[abs(yPlane[n0] - cY).toInt().coerceIn(0, 255)]
                        sumCb += inCb[n0] * w0; sumCr += inCr[n0] * w0; sumW += w0
                        val n1 = rPrev + x; val w1 = lumaEdgeLut[abs(yPlane[n1] - cY).toInt().coerceIn(0, 255)]
                        sumCb += inCb[n1] * w1; sumCr += inCr[n1] * w1; sumW += w1
                        val n2 = rPrev + xNext; val w2 = lumaEdgeLut[abs(yPlane[n2] - cY).toInt().coerceIn(0, 255)]
                        sumCb += inCb[n2] * w2; sumCr += inCr[n2] * w2; sumW += w2
                        val n3 = row + xPrev; val w3 = lumaEdgeLut[abs(yPlane[n3] - cY).toInt().coerceIn(0, 255)]
                        sumCb += inCb[n3] * w3; sumCr += inCr[n3] * w3; sumW += w3
                        val n4 = row + xNext; val w4 = lumaEdgeLut[abs(yPlane[n4] - cY).toInt().coerceIn(0, 255)]
                        sumCb += inCb[n4] * w4; sumCr += inCr[n4] * w4; sumW += w4
                        val n5 = rNext + xPrev; val w5 = lumaEdgeLut[abs(yPlane[n5] - cY).toInt().coerceIn(0, 255)]
                        sumCb += inCb[n5] * w5; sumCr += inCr[n5] * w5; sumW += w5
                        val n6 = rNext + x; val w6 = lumaEdgeLut[abs(yPlane[n6] - cY).toInt().coerceIn(0, 255)]
                        sumCb += inCb[n6] * w6; sumCr += inCr[n6] * w6; sumW += w6
                        val n7 = rNext + xNext; val w7 = lumaEdgeLut[abs(yPlane[n7] - cY).toInt().coerceIn(0, 255)]
                        sumCb += inCb[n7] * w7; sumCr += inCr[n7] * w7; sumW += w7

                        val invW = 1f / sumW
                        outCb[i] = sumCb * invW
                        outCr[i] = sumCr * invW
                    }
                }
            }
            val tmpCb = srcCb; srcCb = dstCb; dstCb = tmpCb
            val tmpCr = srcCr; srcCr = dstCr; dstCr = tmpCr
        }

        val finalCb = srcCb
        val finalCr = srcCr
        forEachRowChunk(height) { yStart, yEnd ->
            val idxStart = yStart * width
            val idxEnd = yEnd * width
            for (i in idxStart until idxEnd) {
                val cY = yPlane[i]
                val r = (cY + finalCr[i]).toInt().coerceIn(0, 255)
                val b = (cY + finalCb[i]).toInt().coerceIn(0, 255)
                val g = ((cY - 0.299f * r - 0.114f * b) * (1f / 0.587f)).toInt().coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private data class LumaStats(
        val mean: Float,
        val p92: Float,
        val p96: Float,
        val clippedPercent: Float,
    )

    /**
     * Fast subsampled luminance statistics (mean, p92, p96, and clipped-highlight %) for adaptive
     * Night Sight gain & highlight protection.
     */
    private fun estimateLumaStats(pixels: IntArray, width: Int, height: Int): LumaStats {
        val bins = IntArray(64)
        val stepX = max(1, width / 64)
        val stepY = max(1, height / 64)
        var sum = 0f
        var clipped = 0
        var count = 0
        for (y in 0 until height step stepY) {
            val row = y * width
            for (x in 0 until width step stepX) {
                val rawLuma = lumaOf(pixels[row + x])
                if (rawLuma >= 246f) clipped++
                val luma = rawLuma * (1f / 255f)
                sum += luma
                bins[(luma * 63f).toInt().coerceIn(0, 63)]++
                count++
            }
        }
        if (count == 0) return LumaStats(mean = 0.2f, p92 = 0.5f, p96 = 0.6f, clippedPercent = 0f)
        val mean = (sum / count).coerceIn(0f, 1f)
        val p92Target = (count * 0.92f).toInt()
        val p96Target = (count * 0.96f).toInt()
        var acc = 0
        var p92 = 0.8f
        var p96 = 0.85f
        var found92 = false
        for (b in 0 until 64) {
            acc += bins[b]
            if (!found92 && acc >= p92Target) {
                p92 = (b + 0.5f) / 64f
                found92 = true
            }
            if (acc >= p96Target) {
                p96 = (b + 0.5f) / 64f
                break
            }
        }
        val clippedPct = (clipped * 100f / count).coerceIn(0f, 100f)
        return LumaStats(mean = mean, p92 = p92, p96 = p96, clippedPercent = clippedPct)
    }

    private fun cloneNativeGainmapIfPresent(bitmap: Bitmap): Gainmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        if (!bitmap.hasGainmap()) return null
        return runCatching {
            val src = bitmap.gainmap ?: return@runCatching null
            val srcContents = src.gainmapContents
            if (srcContents.isRecycled) return@runCatching null
            val contentsCopy = srcContents.copy(
                srcContents.config ?: Bitmap.Config.ALPHA_8,
                false,
            ) ?: return@runCatching null
            Gainmap(contentsCopy).apply {
                val minR = src.ratioMin
                val maxR = src.ratioMax
                val gam = src.gamma
                val epsS = src.epsilonSdr
                val epsH = src.epsilonHdr
                setRatioMin(minR[0], minR[1], minR[2])
                setRatioMax(maxR[0], maxR[1], maxR[2])
                setGamma(gam[0], gam[1], gam[2])
                setEpsilonSdr(epsS[0], epsS[1], epsS[2])
                setEpsilonHdr(epsH[0], epsH[1], epsH[2])
                minDisplayRatioForHdrTransition = src.minDisplayRatioForHdrTransition
                displayRatioForFullHdr = src.displayRatioForFullHdr
            }
        }.getOrNull()
    }

    private fun recycleGainmap(gainmap: Gainmap?) {
        if (gainmap == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        runCatching {
            val contents = gainmap.gainmapContents
            if (!contents.isRecycled) contents.recycle()
        }
    }

    /**
     * Synthesizes a smooth 1/4-resolution logarithmic HDR Gainmap from recovered highlight headroom
     * and attaches it via Android 14+ (API 34+) Bitmap.setGainmap for native OLED Ultra HDR output.
     */
    private fun attachUltraHdrGainmap(
        baseBitmap: Bitmap,
        headroomPlane: FloatArray,
        width: Int,
        height: Int,
        maxBoostRatio: Float,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return runCatching {
            val gw = (width / 4).coerceAtLeast(16)
            val gh = (height / 4).coerceAtLeast(16)
            val rawGrid = FloatArray(gw * gh)

            for (gy in 0 until gh) {
                val y0 = (gy * height) / gh
                val y1 = (((gy + 1) * height) / gh).coerceAtLeast(y0 + 1)
                for (gx in 0 until gw) {
                    val x0 = (gx * width) / gw
                    val x1 = (((gx + 1) * width) / gw).coerceAtLeast(x0 + 1)
                    var sum = 0f
                    var cnt = 0
                    for (y in y0 until y1) {
                        val row = y * width
                        for (x in x0 until x1) {
                            sum += headroomPlane[row + x]
                            cnt++
                        }
                    }
                    rawGrid[gy * gw + gx] = if (cnt > 0) sum / cnt else 0f
                }
            }

            // Smooth 3x3 pass eliminates any block edges in OLED highlight glow transitions
            val gainPixels = IntArray(gw * gh)
            for (gy in 0 until gh) {
                val rPrev = (gy - 1).coerceAtLeast(0) * gw
                val rCurr = gy * gw
                val rNext = (gy + 1).coerceAtMost(gh - 1) * gw
                for (gx in 0 until gw) {
                    val cPrev = (gx - 1).coerceAtLeast(0)
                    val cNext = (gx + 1).coerceAtMost(gw - 1)
                    val avg = (
                        rawGrid[rCurr + gx] * 4f +
                            rawGrid[rCurr + cPrev] + rawGrid[rCurr + cNext] +
                            rawGrid[rPrev + gx] + rawGrid[rNext + gx]
                        ) * (1f / 8f)
                    val gVal = (avg.pow(0.85f) * 255f).toInt().coerceIn(0, 255)
                    gainPixels[rCurr + gx] = (0xFF shl 24) or (gVal shl 16) or (gVal shl 8) or gVal
                }
            }

            val gainBitmap = Bitmap.createBitmap(gainPixels, gw, gh, Bitmap.Config.ARGB_8888)
            val gainmap = Gainmap(gainBitmap).apply {
                setRatioMin(1f, 1f, 1f)
                setRatioMax(maxBoostRatio, maxBoostRatio, maxBoostRatio)
                setGamma(1.0f, 1.0f, 1.0f)
                setEpsilonSdr(0.015625f, 0.015625f, 0.015625f)
                setEpsilonHdr(0.015625f, 0.015625f, 0.015625f)
                setMinDisplayRatioForHdrTransition(1.0f)
                setDisplayRatioForFullHdr(maxBoostRatio)
            }
            baseBitmap.gainmap = gainmap
            baseBitmap.hasGainmap()
        }.getOrDefault(false)
    }

    private suspend inline fun forEachRowChunk(
        height: Int,
        crossinline block: (yStart: Int, yEnd: Int) -> Unit,
    ) {
        val workers = if (height < 64) 1 else workerCount.coerceAtMost(height)
        if (workers <= 1) {
            block(0, height)
            return
        }
        coroutineScope {
            val jobs = Array(workers) { idx ->
                val y0 = (idx * height) / workers
                val y1 = ((idx + 1) * height) / workers
                async(Dispatchers.Default) {
                    if (y1 > y0) block(y0, y1)
                }
            }
            jobs.forEach { it.await() }
        }
    }

    private suspend inline fun <T> forEachRowChunkWithResult(
        height: Int,
        crossinline block: (yStart: Int, yEnd: Int) -> T,
    ): List<T> {
        val workers = if (height < 64) 1 else workerCount.coerceAtMost(height)
        if (workers <= 1) {
            return listOf(block(0, height))
        }
        return coroutineScope {
            val jobs = List(workers) { idx ->
                val y0 = (idx * height) / workers
                val y1 = ((idx + 1) * height) / workers
                async(Dispatchers.Default) {
                    block(y0, y1)
                }
            }
            jobs.awaitAll()
        }
    }

    private fun mergeHistograms(chunkBins: List<IntArray>): List<Int> {
        val bins = IntArray(32)
        for (chunk in chunkBins) {
            for (b in 0 until 32) bins[b] += chunk[b]
        }
        val maxVal = bins.maxOrNull()?.coerceAtLeast(1) ?: 1
        return bins.map { ((it * 100f) / maxVal).toInt().coerceIn(0, 100) }
    }

    private fun buildExpDiffLut(sigma: Float): FloatArray {
        val invTwoSigmaSq = 1f / (2f * sigma * sigma)
        return FloatArray(256) { diff ->
            exp(-(diff * diff) * invTwoSigmaSq)
        }
    }

    private fun buildNormalizedExpLut(sigma: Float): FloatArray {
        val invTwoSigmaSq = 1f / (2f * sigma * sigma)
        return FloatArray(256) { idx ->
            val v = idx / 255f
            exp(-(v * v) * invTwoSigmaSq)
        }
    }

    private fun buildToneLut(
        shadowLift: Float,
        highlightRecover: Float,
        highlightAdjust: Float,
        backlightDehaze: Float = 0f,
        backlightClarity: Float = 0f,
    ): FloatArray {
        val lut = FloatArray(256)
        for (idx in 0..255) {
            var v = idx / 255f

            // Anti-veiling glare: re-anchor the black level if glare washed it out
            if (backlightDehaze > 0.005f) {
                val flareFloor = backlightDehaze * 0.65f
                v = if (v > flareFloor) {
                    ((v - flareFloor) / (1f - flareFloor)).coerceIn(0f, 1f)
                } else {
                    v * 0.5f // smoothly compress deepest blacks
                }
            }

            if (shadowLift > 0.001f) {
                val w = (1f - v).pow(2.2f) * (1f - (1f - v).pow(4f))
                v += shadowLift * w * 0.55f
            } else if (shadowLift < -0.001f) {
                val w = (1f - v).pow(1.9f) * v
                v += shadowLift * w * 1.15f
            }

            // Backlight subject fill with midtone contrast preservation (prevents soapy flat gray skin)
            if (backlightClarity > 0.005f) {
                val subjectWeight = if (v in 0.04f..0.70f) {
                    val norm = (v - 0.04f) / 0.66f
                    (norm * (1f - norm) * 4f).pow(1.2f)
                } else 0f
                v += backlightClarity * subjectWeight * 0.16f
            }

            if (highlightRecover > 0.01f && v > 0.45f) {
                val excess = (v - 0.45f) / 0.55f
                val compressed = 0.45f + 0.55f * (excess / (1f + excess * highlightRecover * 0.85f))
                v = v * (1f - highlightRecover * 0.55f) + compressed * (highlightRecover * 0.55f)
            }

            if (abs(highlightAdjust) > 0.005f) {
                val hw = v.pow(1.7f) * (1f - v * 0.35f)
                v += highlightAdjust * hw * 0.40f
            }

            lut[idx] = v.coerceIn(0f, 1f)
        }
        return lut
    }

    private fun downscaleIfNeeded(src: Bitmap, maxLongEdge: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longEdge = max(w, h)
        if (longEdge <= maxLongEdge) return src
        val scale = maxLongEdge.toFloat() / longEdge
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    private data class MotionBlurEstimate(
        val blurSeverity: Float,
        val dirStepX: Int,
        val dirStepY: Int,
        val anisotropy: Float,
        val psfStride: Int = 1,
    )

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun buildFacePixelBounds(
        faces: List<DetectedFace>,
        width: Int,
        height: Int,
        padFraction: Float,
    ): IntArray {
        if (faces.isEmpty() || width <= 0 || height <= 0) return IntArray(0)
        val out = IntArray(faces.size * 4)
        var k = 0
        for (f in faces) {
            val padX = f.width * padFraction
            val padY = f.height * padFraction
            out[k++] = ((f.left - padX) * width).toInt().coerceIn(0, width - 1)
            out[k++] = ((f.top - padY) * height).toInt().coerceIn(0, height - 1)
            out[k++] = ((f.right + padX) * width).toInt().coerceIn(0, width - 1)
            out[k++] = ((f.bottom + padY) * height).toInt().coerceIn(0, height - 1)
        }
        return out
    }

    private fun isInsideFaceBounds(x: Int, y: Int, faceBounds: IntArray): Boolean {
        var idx = 0
        val n = faceBounds.size
        while (idx + 3 < n) {
            if (x >= faceBounds[idx] && x <= faceBounds[idx + 2] &&
                y >= faceBounds[idx + 1] && y <= faceBounds[idx + 3]
            ) {
                return true
            }
            idx += 4
        }
        return false
    }

    /**
     * Feathered elliptical mask weight in [0f, 1f] over detected face bounds so skin/detail
     * modulation and face-localized deblurring blend seamlessly with zero rectangular seams.
     */
    private fun faceMaskWeight(x: Int, y: Int, faceBounds: IntArray): Float {
        var maxW = 0f
        var idx = 0
        val n = faceBounds.size
        while (idx + 3 < n) {
            val x0 = faceBounds[idx]
            val y0 = faceBounds[idx + 1]
            val x1 = faceBounds[idx + 2]
            val y1 = faceBounds[idx + 3]
            if (x >= x0 && x <= x1 && y >= y0 && y <= y1) {
                val halfW = max(1f, (x1 - x0) * 0.5f)
                val halfH = max(1f, (y1 - y0) * 0.5f)
                val cx = (x0 + x1) * 0.5f
                val cy = (y0 + y1) * 0.5f
                val dx = (x - cx) / halfW
                val dy = (y - cy) / halfH
                val r2 = dx * dx + dy * dy
                val w = when {
                    r2 <= 0.52f -> 1f
                    r2 >= 1.0f -> 0f
                    else -> 1f - smoothstep(0.72f, 1.0f, sqrt(r2))
                }
                if (w > maxW) maxW = w
                if (maxW >= 0.999f) return 1f
            }
            idx += 4
        }
        return maxW
    }

    /**
     * Estimates directional motion blur & optical softness from 1-px vs 2-px (and 2-px vs 4-px
     * for wider motion streaks) gradient energy across 4 principal axes (0°, 90°, 45°, 135°).
     */
    private fun estimateMotionBlur(
        lumaPlane: FloatArray,
        width: Int,
        height: Int,
        focusPointNormalized: Pair<Float, Float>?,
        detectedFaces: List<DetectedFace>,
        excludeFaceBounds: IntArray = IntArray(0),
    ): MotionBlurEstimate {
        if (width < 16 || height < 16) return MotionBlurEstimate(0f, 1, 0, 0f, 1)
        val primaryFace = detectedFaces.maxByOrNull { it.area }
        val xStart: Int
        val xEnd: Int
        val yStart: Int
        val yEnd: Int
        if (primaryFace != null) {
            xStart = ((primaryFace.left - primaryFace.width * 0.1f) * width).toInt().coerceIn(5, max(5, width - 6))
            xEnd = ((primaryFace.right + primaryFace.width * 0.1f) * width).toInt().coerceIn(xStart + 1, width - 5)
            yStart = ((primaryFace.top - primaryFace.height * 0.1f) * height).toInt().coerceIn(5, max(5, height - 6))
            yEnd = ((primaryFace.bottom + primaryFace.height * 0.1f) * height).toInt().coerceIn(yStart + 1, height - 5)
        } else if (excludeFaceBounds.isNotEmpty()) {
            // Sample across the broader frame (skipping face boxes) so a centered moving portrait
            // never pollutes the background sharpness estimate.
            xStart = (width * 0.10f).toInt().coerceIn(5, max(5, width - 6))
            xEnd = (width * 0.90f).toInt().coerceIn(xStart + 1, width - 5)
            yStart = (height * 0.10f).toInt().coerceIn(5, max(5, height - 6))
            yEnd = (height * 0.90f).toInt().coerceIn(yStart + 1, height - 5)
        } else {
            val cx = (focusPointNormalized?.first ?: 0.5f).coerceIn(0.25f, 0.75f)
            val cy = (focusPointNormalized?.second ?: 0.45f).coerceIn(0.25f, 0.75f)
            val hw = (width * 0.26f).toInt().coerceAtLeast(6)
            val hh = (height * 0.26f).toInt().coerceAtLeast(6)
            xStart = ((cx * width).toInt() - hw).coerceIn(5, max(5, width - 6))
            xEnd = ((cx * width).toInt() + hw).coerceIn(xStart + 1, width - 5)
            yStart = ((cy * height).toInt() - hh).coerceIn(5, max(5, height - 6))
            yEnd = ((cy * height).toInt() + hh).coerceIn(yStart + 1, height - 5)
        }

        val stepX = max(1, (xEnd - xStart) / 64)
        val stepY = max(1, (yEnd - yStart) / 64)
        // Squared-gradient energy ratios across 4 axes:
        // For any step edge, 2 * sum(d1_fwd^2 + d1_bwd^2) / sum(g2^2) == 1.0 (sharp)
        // and drops monotonically to ~0.46 (2px softness), ~0.33 (5px blur), ~0.25 (9px+ blur),
        // completely free of the L1 telescoping cancellation bug where |g1|/|g2| is always 0.50.
        var eH1Sq = 0.0; var eH2Sq = 0.0; var eH1SpanSq = 0.0; var eH4Sq = 0.0
        var eV1Sq = 0.0; var eV2Sq = 0.0; var eV1SpanSq = 0.0; var eV4Sq = 0.0
        var eD1_1Sq = 0.0; var eD1_2Sq = 0.0; var eD1SpanSq = 0.0; var eD1_4Sq = 0.0
        var eD2_1Sq = 0.0; var eD2_2Sq = 0.0; var eD2SpanSq = 0.0; var eD2_4Sq = 0.0
        var edgeSamples = 0

        for (y in yStart until yEnd step stepY) {
            val row = y * width
            val rowUp = (y - 1) * width
            val rowDn = (y + 1) * width
            val rowUp2 = (y - 2) * width
            val rowDn2 = (y + 2) * width
            val rowUp4 = (y - 4) * width
            val rowDn4 = (y + 4) * width
            for (x in xStart until xEnd step stepX) {
                if (excludeFaceBounds.isNotEmpty() && isInsideFaceBounds(x, y, excludeFaceBounds)) {
                    continue
                }
                val c = lumaPlane[row + x]
                val dh2 = (lumaPlane[row + x + 2] - lumaPlane[row + x - 2]).toDouble()
                val dv2 = (lumaPlane[rowDn2 + x] - lumaPlane[rowUp2 + x]).toDouble()
                val dd1_2 = (lumaPlane[rowDn2 + x + 2] - lumaPlane[rowUp2 + x - 2]).toDouble()
                val dd2_2 = (lumaPlane[rowDn2 + x - 2] - lumaPlane[rowUp2 + x + 2]).toDouble()
                if (abs(dh2) > 0.035) {
                    val f = (lumaPlane[row + x + 1] - c).toDouble()
                    val b = (c - lumaPlane[row + x - 1]).toDouble()
                    val s1 = (lumaPlane[row + x + 1] - lumaPlane[row + x - 1]).toDouble()
                    val s4 = (lumaPlane[row + x + 4] - lumaPlane[row + x - 4]).toDouble()
                    eH1Sq += 2.0 * (f * f + b * b)
                    eH2Sq += dh2 * dh2
                    eH1SpanSq += 4.0 * s1 * s1
                    eH4Sq += s4 * s4
                    edgeSamples++
                }
                if (abs(dv2) > 0.035) {
                    val f = (lumaPlane[rowDn + x] - c).toDouble()
                    val b = (c - lumaPlane[rowUp + x]).toDouble()
                    val s1 = (lumaPlane[rowDn + x] - lumaPlane[rowUp + x]).toDouble()
                    val s4 = (lumaPlane[rowDn4 + x] - lumaPlane[rowUp4 + x]).toDouble()
                    eV1Sq += 2.0 * (f * f + b * b)
                    eV2Sq += dv2 * dv2
                    eV1SpanSq += 4.0 * s1 * s1
                    eV4Sq += s4 * s4
                    edgeSamples++
                }
                if (abs(dd1_2) > 0.035) {
                    val f = (lumaPlane[rowDn + x + 1] - c).toDouble()
                    val b = (c - lumaPlane[rowUp + x - 1]).toDouble()
                    val s1 = (lumaPlane[rowDn + x + 1] - lumaPlane[rowUp + x - 1]).toDouble()
                    val s4 = (lumaPlane[rowDn4 + x + 4] - lumaPlane[rowUp4 + x - 4]).toDouble()
                    eD1_1Sq += 2.0 * (f * f + b * b)
                    eD1_2Sq += dd1_2 * dd1_2
                    eD1SpanSq += 4.0 * s1 * s1
                    eD1_4Sq += s4 * s4
                }
                if (abs(dd2_2) > 0.035) {
                    val f = (lumaPlane[rowDn + x - 1] - c).toDouble()
                    val b = (c - lumaPlane[rowUp + x + 1]).toDouble()
                    val s1 = (lumaPlane[rowDn + x - 1] - lumaPlane[rowUp + x + 1]).toDouble()
                    val s4 = (lumaPlane[rowDn4 + x - 4] - lumaPlane[rowUp4 + x + 4]).toDouble()
                    eD2_1Sq += 2.0 * (f * f + b * b)
                    eD2_2Sq += dd2_2 * dd2_2
                    eD2SpanSq += 4.0 * s1 * s1
                    eD2_4Sq += s4 * s4
                }
            }
        }

        val totalG2Sq = eH2Sq + eV2Sq
        if (edgeSamples < 12 || totalG2Sq < 1e-4) {
            return MotionBlurEstimate(0f, 1, 0, 0f, 1)
        }

        // Sharp step edge / crisp camera JPEG -> ratio 0.60..1.0 (softness = 0);
        // 2px optical softness -> ~0.46 (~0.36); 5px motion blur -> ~0.33 (~0.75); 9px+ blur -> ~0.25 (1.0).
        fun axisSoftness(numSq: Double, denSq: Double): Float {
            if (denSq < 0.04) return -1f
            val r = (numSq / denSq).toFloat()
            return ((0.58f - r) / 0.33f).coerceIn(0f, 1f)
        }

        val softH = axisSoftness(eH1Sq, eH2Sq)
        val softV = axisSoftness(eV1Sq, eV2Sq)
        val softD1 = axisSoftness(eD1_1Sq, eD1_2Sq)
        val softD2 = axisSoftness(eD2_1Sq, eD2_2Sq)

        // Compare orthogonal axis pairs so pure horizontal/vertical edges never alias onto diagonals
        val hasH = softH >= 0f
        val hasV = softV >= 0f
        var bestDx = 1
        var bestDy = 0
        var bestSoft = 0f
        var aniso = 0f
        var bestWideSoft = 0f

        when {
            hasH && hasV -> {
                if (softH >= softV) {
                    bestDx = 1; bestDy = 0; bestSoft = softH
                    aniso = (softH - softV).coerceIn(0f, 1f)
                    bestWideSoft = axisSoftness(eH1SpanSq, eH4Sq).coerceAtLeast(0f)
                } else {
                    bestDx = 0; bestDy = 1; bestSoft = softV
                    aniso = (softV - softH).coerceIn(0f, 1f)
                    bestWideSoft = axisSoftness(eV1SpanSq, eV4Sq).coerceAtLeast(0f)
                }
                // Only choose a diagonal blur axis when both cardinal axes have edges and the diagonal is clearly softer
                if (softD1 >= 0f && softD2 >= 0f) {
                    val diagDiff = abs(softD1 - softD2)
                    val maxDiag = max(softD1, softD2)
                    if (maxDiag > bestSoft + 0.04f && diagDiff > aniso) {
                        bestDx = 1
                        bestDy = if (softD1 >= softD2) 1 else -1
                        bestSoft = maxDiag
                        aniso = diagDiff.coerceIn(0f, 1f)
                        bestWideSoft = if (softD1 >= softD2) {
                            axisSoftness(eD1SpanSq, eD1_4Sq).coerceAtLeast(0f)
                        } else {
                            axisSoftness(eD2SpanSq, eD2_4Sq).coerceAtLeast(0f)
                        }
                    }
                }
            }
            hasH -> {
                bestDx = 1; bestDy = 0; bestSoft = softH
                aniso = (softH * 0.85f).coerceIn(0f, 1f)
                bestWideSoft = axisSoftness(eH1SpanSq, eH4Sq).coerceAtLeast(0f)
            }
            hasV -> {
                bestDx = 0; bestDy = 1; bestSoft = softV
                aniso = (softV * 0.85f).coerceIn(0f, 1f)
                bestWideSoft = axisSoftness(eV1SpanSq, eV4Sq).coerceAtLeast(0f)
            }
            softD1 >= 0f || softD2 >= 0f -> {
                val s1 = softD1.coerceAtLeast(0f)
                val s2 = softD2.coerceAtLeast(0f)
                bestDx = 1
                bestDy = if (s1 >= s2) 1 else -1
                bestSoft = max(s1, s2)
                aniso = (abs(s1 - s2)).coerceIn(0f, 1f)
                bestWideSoft = if (s1 >= s2) {
                    axisSoftness(eD1SpanSq, eD1_4Sq).coerceAtLeast(0f)
                } else {
                    axisSoftness(eD2SpanSq, eD2_4Sq).coerceAtLeast(0f)
                }
            }
        }

        val globalSoftness = ((0.58f - ((eH1Sq + eV1Sq) / totalG2Sq).toFloat()) / 0.33f).coerceIn(0f, 1f)
        val blurSeverity = max(globalSoftness, bestSoft).coerceIn(0f, 1f)
        // Expand directional PSF stride from ±2px to ±4px when 4px-span ramps confirm wider motion blur
        val psfStride = if (bestWideSoft >= 0.45f && blurSeverity >= 0.45f) 2 else 1
        return MotionBlurEstimate(
            blurSeverity = blurSeverity,
            dirStepX = bestDx,
            dirStepY = bestDy,
            anisotropy = aniso,
            psfStride = psfStride,
        )
    }

    /**
     * Multi-core shock-bounded linear-light Richardson-Lucy luminance deconvolution:
     * - Operates in approximate linear radiance (`l^2` -> RL -> `sqrt`) for accurate highlight/edge physics.
     * - Adapts directional PSF span from ±2px (micro-blur) up to ±4px (wider camera/head shake).
     * - In `faceOnlyMode`, allocates and processes ONLY the compact union ROI around detected faces,
     *   saving ~90% RAM & CPU while leaving a sharp background 100% untouched.
     * - Uses feathered elliptical face masks + smoothstep gradient blending to avoid seams or crunchy skin.
     */
    private suspend fun applyMotionDeblurPass(
        mergedPixels: IntArray,
        lumaPlane: FloatArray,
        width: Int,
        height: Int,
        estimate: MotionBlurEstimate,
        deblurStrength: Float,
        isBacklightActive: Boolean,
        nightSightEnabled: Boolean,
        faceBounds: IntArray,
        faceOnlyMode: Boolean = false,
    ) {
        val hasFaces = faceBounds.isNotEmpty()
        val stride = estimate.psfStride.coerceIn(1, 2)
        val dx1 = estimate.dirStepX * stride
        val dy1 = estimate.dirStepY * stride
        val dx2 = dx1 * 2
        val dy2 = dy1 * 2
        val maxReach = max(1, max(abs(dx2), abs(dy2)))

        // In face-only mode, restrict all allocations and work to the padded union box of detected faces
        val roiX0: Int
        val roiY0: Int
        val roiX1: Int
        val roiY1: Int
        if (faceOnlyMode && hasFaces) {
            var minX = width - 1
            var minY = height - 1
            var maxX = 0
            var maxY = 0
            var k = 0
            while (k + 3 < faceBounds.size) {
                if (faceBounds[k] < minX) minX = faceBounds[k]
                if (faceBounds[k + 1] < minY) minY = faceBounds[k + 1]
                if (faceBounds[k + 2] > maxX) maxX = faceBounds[k + 2]
                if (faceBounds[k + 3] > maxY) maxY = faceBounds[k + 3]
                k += 4
            }
            val margin = maxReach + 2
            roiX0 = (minX - margin).coerceIn(0, width - 1)
            roiY0 = (minY - margin).coerceIn(0, height - 1)
            roiX1 = (maxX + margin + 1).coerceIn(roiX0 + 1, width)
            roiY1 = (maxY + margin + 1).coerceIn(roiY0 + 1, height)
        } else {
            roiX0 = 0
            roiY0 = 0
            roiX1 = width
            roiY1 = height
        }

        val roiW = roiX1 - roiX0
        val roiH = roiY1 - roiY0
        val roiPixels = roiW * roiH
        if (roiW < 8 || roiH < 8) return

        // Convert ROI luminance to approximate linear light (l^2) for physically accurate deconvolution
        val origLin = FloatArray(roiPixels)
        var currentEst = FloatArray(roiPixels)
        var nextEst = FloatArray(roiPixels)
        forEachRowChunk(roiH) { lyStart, lyEnd ->
            for (ly in lyStart until lyEnd) {
                val srcRow = (roiY0 + ly) * width + roiX0
                val dstRow = ly * roiW
                for (lx in 0 until roiW) {
                    val v = lumaPlane[srcRow + lx].coerceIn(0f, 1f)
                    val lin = v * v
                    origLin[dstRow + lx] = lin
                    currentEst[dstRow + lx] = lin
                }
            }
        }

        // Blend directional 5-tap motion PSF with isotropic 3x3 optical diffraction PSF
        val dirWeight = if (estimate.anisotropy >= 0.14f) {
            ((estimate.anisotropy - 0.08f) * 1.40f).coerceIn(0.30f, 0.82f)
        } else 0f
        val isoWeight = 1f - dirWeight
        val maxVertReach = if (dirWeight > 0.01f) max(1, abs(dy2)) else 1
        val ringSlots = 2 * maxVertReach + 1

        val iterations = when {
            deblurStrength >= 0.50f && estimate.blurSeverity >= 0.42f -> 3
            deblurStrength >= 0.25f -> 2
            else -> 1
        }
        val iterGainScale = when (iterations) {
            3 -> 1.85f
            2 -> 1.75f
            else -> 1.85f
        }
        val baseNoiseFloor = when {
            nightSightEnabled -> 0.026f
            isBacklightActive -> 0.011f
            else -> 0.015f
        }

        fun convolvePsf(src: FloatArray, x: Int, y: Int): Float {
            val row = y * roiW
            val xm1 = (x - 1).coerceAtLeast(0)
            val xp1 = (x + 1).coerceAtMost(roiW - 1)
            val ym1 = (y - 1).coerceAtLeast(0) * roiW
            val yp1 = (y + 1).coerceAtMost(roiH - 1) * roiW

            val iso = src[row + x] * 0.25f +
                (src[row + xm1] + src[row + xp1] + src[ym1 + x] + src[yp1 + x]) * 0.125f +
                (src[ym1 + xm1] + src[ym1 + xp1] + src[yp1 + xm1] + src[yp1 + xp1]) * 0.0625f

            if (dirWeight <= 0.01f) return iso

            val mx1 = (x - dx1).coerceIn(0, roiW - 1)
            val px1 = (x + dx1).coerceIn(0, roiW - 1)
            val my1 = (y - dy1).coerceIn(0, roiH - 1) * roiW
            val py1 = (y + dy1).coerceIn(0, roiH - 1) * roiW
            val mx2 = (x - dx2).coerceIn(0, roiW - 1)
            val px2 = (x + dx2).coerceIn(0, roiW - 1)
            val my2 = (y - dy2).coerceIn(0, roiH - 1) * roiW
            val py2 = (y + dy2).coerceIn(0, roiH - 1) * roiW

            val dir = src[row + x] * 0.28f +
                (src[my1 + mx1] + src[py1 + px1]) * 0.22f +
                (src[my2 + mx2] + src[py2 + px2]) * 0.14f

            return iso * isoWeight + dir * dirWeight
        }

        repeat(iterations) {
            val srcEst = currentEst
            val dstEst = nextEst
            forEachRowChunk(roiH) { lyStart, lyEnd ->
                // Compact rolling ratio ring buffer per thread (5 or 9 rows, stays hot in L2 cache)
                val ratioRing = Array(ringSlots) { FloatArray(roiW) }
                val ringRowTag = IntArray(ringSlots) { Int.MIN_VALUE }

                fun ensureRatioRow(ry: Int): FloatArray {
                    val clampedY = ry.coerceIn(0, roiH - 1)
                    val slot = clampedY % ringSlots
                    val buf = ratioRing[slot]
                    if (ringRowTag[slot] != clampedY) {
                        val rowOff = clampedY * roiW
                        for (x in 0 until roiW) {
                            val blurred = convolvePsf(srcEst, x, clampedY)
                            val orig = origLin[rowOff + x]
                            buf[x] = (orig / max(blurred, 0.0025f)).coerceIn(0.55f, 1.65f)
                        }
                        ringRowTag[slot] = clampedY
                    }
                    return buf
                }

                for (ly in lyStart until lyEnd) {
                    // Populate the rolling ring window for all vertical PSF taps
                    for (ry in (ly - maxVertReach)..(ly + maxVertReach)) {
                        ensureRatioRow(ry)
                    }
                    val r0 = ratioRing[ly.coerceIn(0, roiH - 1) % ringSlots]
                    val rIsoM1 = ratioRing[(ly - 1).coerceIn(0, roiH - 1) % ringSlots]
                    val rIsoP1 = ratioRing[(ly + 1).coerceIn(0, roiH - 1) % ringSlots]
                    val rDirM1 = ratioRing[(ly - dy1).coerceIn(0, roiH - 1) % ringSlots]
                    val rDirP1 = ratioRing[(ly + dy1).coerceIn(0, roiH - 1) % ringSlots]
                    val rDirM2 = ratioRing[(ly - dy2).coerceIn(0, roiH - 1) % ringSlots]
                    val rDirP2 = ratioRing[(ly + dy2).coerceIn(0, roiH - 1) % ringSlots]

                    val rowOff = ly * roiW
                    val ym1 = (ly - 1).coerceAtLeast(0) * roiW
                    val yp1 = (ly + 1).coerceAtMost(roiH - 1) * roiW
                    val globalY = roiY0 + ly

                    for (lx in 0 until roiW) {
                        val xm1 = (lx - 1).coerceAtLeast(0)
                        val xp1 = (lx + 1).coerceAtMost(roiW - 1)
                        val i = rowOff + lx
                        val curLin = srcEst[i]
                        val globalX = roiX0 + lx

                        val faceW = if (hasFaces) faceMaskWeight(globalX, globalY, faceBounds) else 0f
                        if (faceOnlyMode && faceW <= 0.001f) {
                            dstEst[i] = curLin
                            continue
                        }

                        // Local 3x3 linear bounds & perceptual gradient for zero-ringing regularization
                        val n0 = srcEst[ym1 + xm1]; val n1 = srcEst[ym1 + lx]; val n2 = srcEst[ym1 + xp1]
                        val n3 = srcEst[rowOff + xm1]; val n5 = srcEst[rowOff + xp1]
                        val n6 = srcEst[yp1 + xm1]; val n7 = srcEst[yp1 + lx]; val n8 = srcEst[yp1 + xp1]
                        val minLin = min(curLin, min(min(min(n0, n1), min(n2, n3)), min(min(n5, n6), min(n7, n8))))
                        val maxLin = max(curLin, max(max(max(n0, n1), max(n2, n3)), max(max(n5, n6), max(n7, n8))))
                        val perceptualGrad = sqrt(maxLin.coerceAtLeast(0f)) - sqrt(minLin.coerceAtLeast(0f))

                        val texGate = ((perceptualGrad - baseNoiseFloor) / 0.036f).coerceIn(0f, 1f)
                        if (texGate <= 0.001f) {
                            dstEst[i] = curLin
                            continue
                        }

                        // Backproject symmetric PSF over the cached ratio rows
                        val isoCorr = r0[lx] * 0.25f +
                            (r0[xm1] + r0[xp1] + rIsoM1[lx] + rIsoP1[lx]) * 0.125f +
                            (rIsoM1[xm1] + rIsoM1[xp1] + rIsoP1[xm1] + rIsoP1[xp1]) * 0.0625f

                        val correction = if (dirWeight > 0.01f) {
                            val mx1 = (lx - dx1).coerceIn(0, roiW - 1)
                            val px1 = (lx + dx1).coerceIn(0, roiW - 1)
                            val mx2 = (lx - dx2).coerceIn(0, roiW - 1)
                            val px2 = (lx + dx2).coerceIn(0, roiW - 1)
                            val dirCorr = r0[lx] * 0.28f +
                                (rDirM1[mx1] + rDirP1[px1]) * 0.22f +
                                (rDirM2[mx2] + rDirP2[px2]) * 0.14f
                            isoCorr * isoWeight + dirCorr * dirWeight
                        } else isoCorr

                        val regionMod = if (faceW > 0.001f) {
                            // Smoothstep skin protection (0.44x on smooth skin -> 1.28x on eyes/brows/lips/hair)
                            val edgeBlend = smoothstep(0.032f, 0.080f, perceptualGrad)
                            val faceMod = 0.44f + (1.28f - 0.44f) * edgeBlend
                            if (faceOnlyMode) faceMod * faceW else 1f + (faceMod - 1f) * faceW
                        } else 1.0f

                        val curPerc = sqrt(curLin.coerceAtLeast(0f))
                        val backlightMod = if (isBacklightActive) {
                            when {
                                curPerc < 0.65f -> 1f + (1f - curPerc) * 0.28f
                                curPerc > 0.85f -> 0.25f // Prevent silhouette halos against blown sky
                                else -> 1f
                            }
                        } else 1f

                        val gain = (deblurStrength * iterGainScale * regionMod * backlightMod * texGate)
                            .coerceIn(0f, 2.65f)
                        val updated = curLin * (1f + (correction - 1f) * gain)
                        // Strict local extrema clamping [minLin, maxLin] (shock-bounded RL):
                        // Steepens blurred edge ramps cleanly while making dark/bright ringing halos impossible.
                        dstEst[i] = updated.coerceIn(minLin, maxLin)
                    }
                }
            }
            val tmp = currentEst
            currentEst = nextEst
            nextEst = tmp
        }

        // Convert recovered linear luminance back to sRGB luma and apply cleanly to RGB pixels
        val finalLin = currentEst
        forEachRowChunk(roiH) { lyStart, lyEnd ->
            for (ly in lyStart until lyEnd) {
                val srcRow = ly * roiW
                val dstRow = (roiY0 + ly) * width + roiX0
                for (lx in 0 until roiW) {
                    val dstIdx = dstRow + lx
                    val oL = lumaPlane[dstIdx]
                    val nL = sqrt(finalLin[srcRow + lx].coerceIn(0f, 1f))
                    lumaPlane[dstIdx] = nL
                    val deltaL = nL - oL
                    if (abs(deltaL) > 0.0015f) {
                        val p = mergedPixels[dstIdx]
                        val r = ((p shr 16) and 0xFF).toFloat()
                        val g = ((p shr 8) and 0xFF).toFloat()
                        val b = (p and 0xFF).toFloat()
                        val nr: Int
                        val ng: Int
                        val nb: Int
                        if (oL > 0.04f) {
                            val ratio = (nL / oL).coerceIn(0.62f, 1.48f)
                            nr = (r * ratio).toInt().coerceIn(0, 255)
                            ng = (g * ratio).toInt().coerceIn(0, 255)
                            nb = (b * ratio).toInt().coerceIn(0, 255)
                        } else {
                            val add = deltaL * 255f
                            nr = (r + add).toInt().coerceIn(0, 255)
                            ng = (g + add).toInt().coerceIn(0, 255)
                            nb = (b + add).toInt().coerceIn(0, 255)
                        }
                        mergedPixels[dstIdx] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                    }
                }
            }
        }
    }

    /**
     * Evaluates high-frequency focus sharpness around detected faces (90% weight when faces exist)
     * and the tap-to-focus ROI (or frame center) so bursts always pick the sharpest-face reference.
     */
    private fun computeRoiSharpness(
        pixels: IntArray,
        w: Int,
        h: Int,
        focusPointNormalized: Pair<Float, Float>?,
        detectedFaces: List<DetectedFace> = emptyList(),
    ): Float {
        if (w < 6 || h < 6) return 0f

        fun regionSharpness(xStart: Int, xEnd: Int, yStart: Int, yEnd: Int): Float {
            val sx0 = xStart.coerceIn(2, max(2, w - 4))
            val sx1 = xEnd.coerceIn(sx0 + 1, w - 2)
            val sy0 = yStart.coerceIn(2, max(2, h - 4))
            val sy1 = yEnd.coerceIn(sy0 + 1, h - 2)
            val step = if ((sx1 - sx0) > 320) 2 else 1
            var energy = 0f
            var lumaSum = 1f
            var sampleCount = 0
            for (y in sy0 until sy1 step step) {
                val row = y * w
                val rowUp = (y - 1) * w
                val rowDown = (y + 1) * w
                for (x in sx0 until sx1 step step) {
                    val c = lumaOf(pixels[row + x])
                    val l = lumaOf(pixels[row + x - 1])
                    val r = lumaOf(pixels[row + x + 1])
                    val u = lumaOf(pixels[rowUp + x])
                    val d = lumaOf(pixels[rowDown + x])
                    energy += abs(4f * c - l - r - u - d) + abs(r - l) * 0.5f + abs(d - u) * 0.5f
                    lumaSum += c
                    sampleCount++
                }
            }
            return energy / (lumaSum + sampleCount * 8f)
        }

        val cx = (focusPointNormalized?.first ?: 0.5f).coerceIn(0.2f, 0.8f)
        val cy = (focusPointNormalized?.second ?: 0.45f).coerceIn(0.2f, 0.8f)
        val halfW = (w * 0.28f).toInt().coerceAtLeast(8)
        val halfH = (h * 0.28f).toInt().coerceAtLeast(8)
        val baseSharpness = regionSharpness(
            xStart = (cx * w).toInt() - halfW,
            xEnd = (cx * w).toInt() + halfW,
            yStart = (cy * h).toInt() - halfH,
            yEnd = (cy * h).toInt() + halfH,
        )

        if (detectedFaces.isEmpty()) return baseSharpness

        var weightedFaceSum = 0f
        var areaWeightSum = 0f
        for (face in detectedFaces) {
            // Inset slightly into the core facial features (eyes, brows, nose, mouth) so high-contrast
            // backlit backgrounds/foliage just outside the head never pollute face sharpness scoring.
            val insetX = face.width * 0.08f
            val insetY = face.height * 0.08f
            val fSharp = regionSharpness(
                xStart = ((face.left + insetX) * w).toInt(),
                xEnd = ((face.right - insetX) * w).toInt(),
                yStart = ((face.top + insetY) * h).toInt(),
                yEnd = ((face.bottom - insetY) * h).toInt(),
            )
            val areaW = face.area.coerceAtLeast(0.005f)
            weightedFaceSum += fSharp * areaW
            areaWeightSum += areaW
        }
        val faceSharpness = if (areaWeightSum > 0f) weightedFaceSum / areaWeightSum else baseSharpness
        return faceSharpness * 0.90f + baseSharpness * 0.10f
    }

    private fun meanOfArray(values: FloatArray): Float {
        if (values.isEmpty()) return 128f
        var sum = 0f
        for (v in values) sum += v
        return sum / values.size
    }

    private fun createLumaThumbnailFromPlane(lumaPlane: FloatArray, srcW: Int, srcH: Int, tw: Int, th: Int): FloatArray {
        val thumb = FloatArray(tw * th)
        for (ty in 0 until th) {
            val sy = (ty * srcH / th).coerceIn(0, srcH - 1)
            val sRow = sy * srcW
            val tRow = ty * tw
            for (tx in 0 until tw) {
                val sx = (tx * srcW / tw).coerceIn(0, srcW - 1)
                thumb[tRow + tx] = lumaPlane[sRow + sx]
            }
        }
        return thumb
    }

    private fun createLumaThumbnail(pixels: IntArray, srcW: Int, srcH: Int, tw: Int, th: Int): FloatArray {
        val thumb = FloatArray(tw * th)
        for (ty in 0 until th) {
            val sy = (ty * srcH / th).coerceIn(0, srcH - 1)
            val sRow = sy * srcW
            val tRow = ty * tw
            for (tx in 0 until tw) {
                val sx = (tx * srcW / tw).coerceIn(0, srcW - 1)
                thumb[tRow + tx] = lumaOf(pixels[sRow + sx])
            }
        }
        return thumb
    }

    private fun estimateCoarseShift(
        refThumb: FloatArray,
        targetThumb: FloatArray,
        tw: Int,
        th: Int,
        fullW: Int,
        fullH: Int,
        exposureGain: Float,
        skipClippedRef: Boolean = false,
        skipClippedOther: Boolean = false,
    ): Pair<Int, Int> {
        val searchRadius = 12
        var bestDx = 0
        var bestDy = 0
        var bestMeanSad = Float.MAX_VALUE
        val margin = searchRadius + 4

        for (dy in -searchRadius..searchRadius) {
            for (dx in -searchRadius..searchRadius) {
                var sad = 0f
                var cnt = 0
                for (y in margin until (th - margin) step 2) {
                    val rRow = y * tw
                    val tRow = (y + dy) * tw
                    for (x in margin until (tw - margin) step 2) {
                        val refVal = refThumb[rRow + x]
                        val otherRaw = targetThumb[tRow + x + dx]
                        if (skipClippedRef && refVal >= 242f) continue
                        if (skipClippedOther && otherRaw >= 242f) continue
                        val normTarget = (otherRaw * exposureGain).coerceAtMost(255f)
                        sad += abs(refVal - normTarget)
                        cnt++
                    }
                }
                if (cnt > 0) {
                    val meanSad = sad / cnt
                    if (meanSad < bestMeanSad) {
                        bestMeanSad = meanSad
                        bestDx = dx
                        bestDy = dy
                    }
                }
            }
        }

        val fullDx = (bestDx * fullW / tw.toFloat()).toInt()
        val fullDy = (bestDy * fullH / th.toFloat()).toInt()
        return fullDx to fullDy
    }

    private fun refineGlobalShiftOnePx(
        refLumaPlane: FloatArray,
        otherPixels: IntArray,
        width: Int,
        height: Int,
        initDx: Int,
        initDy: Int,
        exposureGain: Float,
        skipClippedRef: Boolean = false,
        skipClippedOther: Boolean = false,
    ): Triple<Int, Int, Float> {
        val fineRadius = 8
        val maxMargin = (abs(initDx) + abs(initDy) + fineRadius + 4).coerceAtMost(min(width, height) / 3)
        val xStart = maxMargin.coerceAtLeast(2)
        val xEnd = (width - maxMargin).coerceAtLeast(xStart + 2)
        val yStart = maxMargin.coerceAtLeast(2)
        val yEnd = (height - maxMargin).coerceAtLeast(yStart + 2)
        val step = max(2, min(width, height) / 96)

        var bestDx = initDx
        var bestDy = initDy
        var bestMeanSad = Float.MAX_VALUE
        var sampleCount = 0

        for (oy in -fineRadius..fineRadius) {
            val dy = initDy + oy
            for (ox in -fineRadius..fineRadius) {
                val dx = initDx + ox
                var sad = 0f
                var cnt = 0
                for (y in yStart until yEnd step step) {
                    val sy = y + dy
                    if (sy < 0 || sy >= height) continue
                    val rRow = y * width
                    val sRow = sy * width
                    for (x in xStart until xEnd step step) {
                        val sx = x + dx
                        if (sx < 0 || sx >= width) continue
                        val refL = refLumaPlane[rRow + x]
                        val otherRaw = lumaOf(otherPixels[sRow + sx])
                        if (skipClippedRef && refL >= 242f) continue
                        if (skipClippedOther && otherRaw >= 242f) continue
                        val otherL = (otherRaw * exposureGain).coerceAtMost(255f)
                        sad += abs(refL - otherL)
                        cnt++
                    }
                }
                if (cnt > 0) {
                    val meanSad = sad / cnt
                    if (meanSad < bestMeanSad) {
                        bestMeanSad = meanSad
                        bestDx = dx
                        bestDy = dy
                        sampleCount = cnt
                    }
                }
            }
        }

        val normMeanDiff = if (sampleCount > 0) bestMeanSad / 255f else 0.25f
        val confidence = (1f - normMeanDiff * 3.8f).coerceIn(0.35f, 0.99f)
        return Triple(bestDx, bestDy, confidence)
    }

    private fun refineLocalTileShifts(
        refLumaPlane: FloatArray,
        otherPixels: IntArray,
        width: Int,
        height: Int,
        globalDx: Int,
        globalDy: Int,
        tileCols: Int,
        tileRows: Int,
        exposureGain: Float,
        skipClippedRef: Boolean = false,
        skipClippedOther: Boolean = false,
    ): IntArray {
        val result = IntArray(tileCols * tileRows * 2)
        val localRadius = 3
        val stepX = max(4, (width / tileCols) / 14)
        val stepY = max(4, (height / tileRows) / 14)

        for (ty in 0 until tileRows) {
            val y0 = (ty * height) / tileRows + 4
            val y1 = (((ty + 1) * height) / tileRows - 4).coerceAtLeast(y0 + 1)
            for (tx in 0 until tileCols) {
                val x0 = (tx * width) / tileCols + 4
                val x1 = (((tx + 1) * width) / tileCols - 4).coerceAtLeast(x0 + 1)

                var bestDx = globalDx
                var bestDy = globalDy
                var bestMeanScore = Float.MAX_VALUE

                for (oy in -localRadius..localRadius) {
                    val dy = globalDy + oy
                    for (ox in -localRadius..localRadius) {
                        val dx = globalDx + ox
                        var sad = 0f
                        var cnt = 0
                        for (y in y0 until y1 step stepY) {
                            val sy = y + dy
                            if (sy < 0 || sy >= height) continue
                            val rRow = y * width
                            val sRow = sy * width
                            for (x in x0 until x1 step stepX) {
                                val sx = x + dx
                                if (sx < 0 || sx >= width) continue
                                val refL = refLumaPlane[rRow + x]
                                val otherRaw = lumaOf(otherPixels[sRow + sx])
                                if (skipClippedRef && refL >= 242f) continue
                                if (skipClippedOther && otherRaw >= 242f) continue
                                val otherL = (otherRaw * exposureGain).coerceAtMost(255f)
                                sad += abs(refL - otherL)
                                cnt++
                            }
                        }
                        if (cnt > 0) {
                            val meanScore = (sad / cnt) + (abs(ox) + abs(oy)) * 0.25f
                            if (meanScore < bestMeanScore) {
                                bestMeanScore = meanScore
                                bestDx = dx
                                bestDy = dy
                            }
                        }
                    }
                }

                val idx = (ty * tileCols + tx) * 2
                result[idx] = bestDx
                result[idx + 1] = bestDy
            }
        }
        return result
    }

    /**
     * Downsamples luminance into a smooth grid and applies 3 passes of separable 5-tap Gaussian
     * [1, 4, 6, 4, 1]/16 horizontal + vertical blur so bilinear interpolation yields zero blockiness.
     */
    private fun computeSmoothGaussianLumaField(
        pixels: IntArray,
        w: Int,
        h: Int,
        lw: Int,
        lh: Int,
    ): FloatArray {
        val field = FloatArray(lw * lh)
        for (ly in 0 until lh) {
            val yStart = (ly * h) / lh
            val yEnd = (((ly + 1) * h) / lh).coerceAtLeast(yStart + 1)
            for (lx in 0 until lw) {
                val xStart = (lx * w) / lw
                val xEnd = (((lx + 1) * w) / lw).coerceAtLeast(xStart + 1)
                var sum = 0f
                var cnt = 0
                for (y in yStart until yEnd step 2) {
                    val row = y * w
                    for (x in xStart until xEnd step 2) {
                        sum += lumaOf(pixels[row + x]) * (1f / 255f)
                        cnt++
                    }
                }
                field[ly * lw + lx] = if (cnt > 0) sum / cnt else 0.5f
            }
        }

        val temp = FloatArray(lw * lh)
        repeat(3) {
            for (y in 0 until lh) {
                val row = y * lw
                for (x in 0 until lw) {
                    val c0 = field[row + (x - 2).coerceIn(0, lw - 1)]
                    val c1 = field[row + (x - 1).coerceIn(0, lw - 1)]
                    val c2 = field[row + x]
                    val c3 = field[row + (x + 1).coerceIn(0, lw - 1)]
                    val c4 = field[row + (x + 2).coerceIn(0, lw - 1)]
                    temp[row + x] = (c0 + 4f * c1 + 6f * c2 + 4f * c3 + c4) * (1f / 16f)
                }
            }
            for (y in 0 until lh) {
                val r0 = (y - 2).coerceIn(0, lh - 1) * lw
                val r1 = (y - 1).coerceIn(0, lh - 1) * lw
                val r2 = y * lw
                val r3 = (y + 1).coerceIn(0, lh - 1) * lw
                val r4 = (y + 2).coerceIn(0, lh - 1) * lw
                for (x in 0 until lw) {
                    field[r2 + x] = (temp[r0 + x] + 4f * temp[r1 + x] + 6f * temp[r2 + x] + 4f * temp[r3 + x] + temp[r4 + x]) * (1f / 16f)
                }
            }
        }
        return field
    }

    fun computeHistogram32(bitmap: Bitmap): List<Int> {
        val bins = IntArray(32)
        val w = bitmap.width
        val h = bitmap.height
        val rowPixels = IntArray(w)
        val stepX = max(1, w / 100)
        val stepY = max(1, h / 100)
        for (y in 0 until h step stepY) {
            bitmap.getPixels(rowPixels, 0, w, 0, y, w, 1)
            for (x in 0 until w step stepX) {
                val luma = lumaOf(rowPixels[x]).toInt().coerceIn(0, 255)
                bins[luma shr 3]++
            }
        }
        val maxVal = bins.maxOrNull()?.coerceAtLeast(1) ?: 1
        return bins.map { ((it * 100f) / maxVal).toInt().coerceIn(0, 100) }
    }

    private fun lumaOf(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    private companion object {
        private const val MAX_PROCESS_LONG_EDGE = CameraController.MAX_CAPTURE_LONG_EDGE
    }
}
