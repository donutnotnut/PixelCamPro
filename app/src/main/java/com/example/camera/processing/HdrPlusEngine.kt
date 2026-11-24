package com.example.camera.processing

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.os.Build
import com.example.camera.hardware.CameraController
import com.example.camera.hardware.RawCapturedFrame
import com.example.camera.model.CameraMode
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
    ): HdrProcessResult = withContext(Dispatchers.Default) {
        require(rawFrames.isNotEmpty()) { "At least one frame is required for processing" }
        val startNs = System.nanoTime()

        // Memory-safe streaming path for ALL multi-frame bursts at full 12.5MP resolution (4096px):
        // evaluates sharpness on compact 640px thumbnails (~1MB), then decodes & merges
        // full-resolution secondary frames one at a time so heap stays bounded.
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
                    val s = computeRoiSharpness(tpx, tw, th, focusPointNormalized)
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
            val wantNativeGainmap = !nightSightEnabled && config.ultraHdrEnabled && hdrMode != HdrMode.OFF
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
                val (alignmentConfidence, effectiveFramesMerged) = if (hdrMode != HdrMode.OFF || nightSightEnabled) {
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
    ): HdrProcessResult = withContext(Dispatchers.Default) {
        require(frames.isNotEmpty()) { "At least one frame is required for processing" }
        val startNs = System.nanoTime()

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
                    computeRoiSharpness(px, w, h, focusPointNormalized)
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

        val (alignmentConfidence, effectiveFramesMerged) = if (!isPixelExtensionCapture &&
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
        if (nightSightEnabled && !isPixelExtensionCapture) {
            val (meanLuma, p92Luma) = estimateLumaStats(mergedPixels, width, height)
            when {
                meanLuma < 0.34f -> {
                    val targetMean = 0.34f
                    val rawGain = (targetMean / meanLuma.coerceAtLeast(0.06f)).coerceIn(1.0f, 1.95f)
                    // Strictly protect highlights: cap drops to 1.0 when p92 is already bright
                    val highlightCap = (0.88f / p92Luma.coerceAtLeast(0.32f)).coerceIn(1.0f, 1.95f)
                    adaptiveNightGain = min(rawGain, highlightCap)
                    // Smooth shadow lift that fades cleanly to 0 as meanLuma approaches 0.34
                    val darknessRatio = ((0.34f - meanLuma) / 0.28f).coerceIn(0f, 1f)
                    adaptiveNightShadowLift = darknessRatio * 0.34f
                }
                meanLuma > 0.54f -> {
                    // Hardware captured slightly hot — pull exposure back smoothly to prevent blown highlights
                    adaptiveNightGain = (0.48f / meanLuma).coerceIn(0.68f, 1.0f)
                    adaptiveNightShadowLift = 0f
                }
                else -> {
                    // Already well-exposed by the long shutter: keep natural exposure, zero extra lift!
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
        val contrastAmount = (manual.contrastAdjust + if (cinematicOn) ToneColorMath.CINEMATIC_CONTRAST_OFFSET else 0f + if (nightSightEnabled && !isPixelExtensionCapture) 0.08f else 0f)
            .coerceIn(-1f, 1f)
        val vibrancyAmount = (manual.vibrancyAdjust + if (cinematicOn) ToneColorMath.CINEMATIC_VIBRANCY_OFFSET else 0f + if (nightSightEnabled && !isPixelExtensionCapture) 0.14f else 0f)
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

        val needSpatialPass = sharpenAmount > 0.01f
        val lumaPlane = if (sharpenAmount > 0.01f) FloatArray(totalPixels) else null
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

                        val luma = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0.0001f, 1.5f)
                        var mappedLuma = luma.coerceIn(0f, 1f)

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

                        val luma = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0.0001f, 1.5f)
                        var mappedLuma = luma.coerceIn(0f, 1f)

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

            // Pass 2 (Parallel): 2-Scale Edge-Preserving Sabre Deconvolution Sharpening + Fused ColorMatrix
            val finalPixels = IntArray(totalPixels)
            val sharpenEdgeLut = if (sharpenAmount > 0.01f) buildNormalizedExpLut(sigma = 0.13f) else null
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

                        if (lumaPlane != null && sharpenEdgeLut != null && sharpenAmount > 0.01f) {
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
                            val combinedDiff = (coredFine * 1.75f + medDetail) * subjectClarityMultiplier
                            if (combinedDiff != 0f) {
                                val edgeMask = sharpenEdgeLut[(abs(fineDetail) * 255f).toInt().coerceIn(0, 255)]
                                val minL = min(centerL, min(min(min(ml, mr), min(tc, bc)), min(min(tl, tr), min(bl, br))))
                                val maxL = max(centerL, max(max(max(ml, mr), max(tc, bc)), max(max(tl, tr), max(bl, br))))
                                // Asymmetric acutance clamp: crisp bright edge micro-contrast without dark ringing halos
                                val maxDarkOvershoot = 0.022f * sharpenAmount
                                val maxBrightOvershoot = 0.036f * sharpenAmount
                                val targetSharpenedL = (centerL + combinedDiff * sharpenAmount * edgeMask)
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
            ),
            lumaHistogram = histogramBins,
        )
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
    ): Pair<Float, Int> {
        val totalPixels = width * height
        val accR = FloatArray(totalPixels)
        val accG = FloatArray(totalPixels)
        val accB = FloatArray(totalPixels)
        val accW = FloatArray(totalPixels)
        val refLumaPlane = FloatArray(totalPixels)
        val refIsEdge = BooleanArray(totalPixels)

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
    ): Pair<Float, Int> {
        val totalPixels = width * height
        val accR = FloatArray(totalPixels)
        val accG = FloatArray(totalPixels)
        val accB = FloatArray(totalPixels)
        val accW = FloatArray(totalPixels)
        val refLumaPlane = FloatArray(totalPixels)
        val refIsEdge = BooleanArray(totalPixels)

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
    ): Float? {
        val isBrightShadowBracket = otherExposureBiasEv > 0.35f
        val isDarkHighlightBracket = otherExposureBiasEv < -0.35f
        val isExposureBracket = isBrightShadowBracket || isDarkHighlightBracket

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

                        if (isBrightShadowBracket) {
                            // Exposure fusion for backlit subjects: inject clean real photons from the +EV
                            // frame directly into shadowed subject pixels without blowing out highlights.
                            if (refLuma < 125f && rawOtherLuma in 18f..242f) {
                                val shadowFactor = ((125f - refLuma) / 105f).coerceIn(0f, 1f)
                                val wellExposedFactor = if (rawOtherLuma > 215f) {
                                    ((242f - rawOtherLuma) / 27f).coerceIn(0f, 1f)
                                } else 1f
                                // Monotonicity ghost check: a static shadow pixel in a +EV frame is brighter
                                // than refLuma and bounded by the physical EV lift + ISP tone curve headroom
                                val expectedMaxLuma = (refLuma * maxBracketLiftScale + 68f).coerceAtMost(245f)
                                if (rawOtherLuma >= refLuma * 0.80f && rawOtherLuma <= expectedMaxLuma) {
                                    val fuseW = (shadowFactor * shadowFactor * wellExposedFactor * 1.45f)
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
                            // Protect crisp micro-edges from sub-pixel misalignment blur: reference frame dominates on edges
                            if (absDiff > edgeRejectDiff) {
                                w = 0f
                            } else if (absDiff > 3.5f) {
                                w *= 0.25f
                            }
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

    /**
     * Fast subsampled luminance statistics (mean + 92nd percentile) for adaptive Night Sight gain.
     */
    private fun estimateLumaStats(pixels: IntArray, width: Int, height: Int): Pair<Float, Float> {
        val bins = IntArray(64)
        val stepX = max(1, width / 64)
        val stepY = max(1, height / 64)
        var sum = 0f
        var count = 0
        for (y in 0 until height step stepY) {
            val row = y * width
            for (x in 0 until width step stepX) {
                val luma = lumaOf(pixels[row + x]) * (1f / 255f)
                sum += luma
                bins[(luma * 63f).toInt().coerceIn(0, 63)]++
                count++
            }
        }
        if (count == 0) return 0.2f to 0.5f
        val mean = (sum / count).coerceIn(0f, 1f)
        val p92Target = (count * 0.92f).toInt()
        var acc = 0
        var p92 = 0.8f
        for (b in 0 until 64) {
            acc += bins[b]
            if (acc >= p92Target) {
                p92 = (b + 0.5f) / 64f
                break
            }
        }
        return mean to p92
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

    /**
     * Evaluates high-frequency focus sharpness directly at full normalized resolution around
     * the tap-to-focus ROI (or frame center).
     */
    private fun computeRoiSharpness(
        pixels: IntArray,
        w: Int,
        h: Int,
        focusPointNormalized: Pair<Float, Float>?,
    ): Float {
        if (w < 6 || h < 6) return 0f
        val cx = (focusPointNormalized?.first ?: 0.5f).coerceIn(0.2f, 0.8f)
        val cy = (focusPointNormalized?.second ?: 0.45f).coerceIn(0.2f, 0.8f)
        val halfW = (w * 0.28f).toInt().coerceAtLeast(8)
        val halfH = (h * 0.28f).toInt().coerceAtLeast(8)
        val xStart = ((cx * w).toInt() - halfW).coerceIn(2, max(2, w - 4))
        val xEnd = ((cx * w).toInt() + halfW).coerceIn(xStart + 1, w - 2)
        val yStart = ((cy * h).toInt() - halfH).coerceIn(2, max(2, h - 4))
        val yEnd = ((cy * h).toInt() + halfH).coerceIn(yStart + 1, h - 2)
        val step = if (w > 640) 2 else 1

        var energy = 0f
        var lumaSum = 1f
        var sampleCount = 0
        for (y in yStart until yEnd step step) {
            val row = y * w
            val rowUp = (y - 1) * w
            val rowDown = (y + 1) * w
            for (x in xStart until xEnd step step) {
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
    ): Pair<Int, Int> {
        val searchRadius = 12
        var bestDx = 0
        var bestDy = 0
        var bestSad = Float.MAX_VALUE
        val margin = searchRadius + 4

        for (dy in -searchRadius..searchRadius) {
            for (dx in -searchRadius..searchRadius) {
                var sad = 0f
                for (y in margin until (th - margin) step 2) {
                    val rRow = y * tw
                    val tRow = (y + dy) * tw
                    for (x in margin until (tw - margin) step 2) {
                        val normTarget = (targetThumb[tRow + x + dx] * exposureGain).coerceAtMost(255f)
                        sad += abs(refThumb[rRow + x] - normTarget)
                    }
                }
                if (sad < bestSad) {
                    bestSad = sad
                    bestDx = dx
                    bestDy = dy
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
        var bestSad = Float.MAX_VALUE
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
                        val otherL = (lumaOf(otherPixels[sRow + sx]) * exposureGain).coerceAtMost(255f)
                        sad += abs(refL - otherL)
                        cnt++
                    }
                }
                if (cnt > 0 && sad < bestSad) {
                    bestSad = sad
                    bestDx = dx
                    bestDy = dy
                    sampleCount = cnt
                }
            }
        }

        val normMeanDiff = if (sampleCount > 0) bestSad / (sampleCount * 255f) else 0.25f
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
                var bestSad = Float.MAX_VALUE

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
                                val otherL = (lumaOf(otherPixels[sRow + sx]) * exposureGain).coerceAtMost(255f)
                                sad += abs(refL - otherL)
                                cnt++
                            }
                        }
                        val priorPenalty = (abs(ox) + abs(oy)) * cnt * 0.25f
                        val score = sad + priorPenalty
                        if (cnt > 0 && score < bestSad) {
                            bestSad = score
                            bestDx = dx
                            bestDy = dy
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
