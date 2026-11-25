package com.example.camera.model

import android.graphics.ColorMatrix
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

enum class CameraMode(val label: String) {
    CAMERA("Photo")
}

enum class HdrMode(val label: String, val badge: String) {
    OFF("Off", "HDR Off"),
    HDR_PLUS("HDR+", "HDR+"),
    HDR_PLUS_ENHANCED("HDR+ Enhanced", "HDR+ Enh")
}

enum class NightSightMode(val label: String, val shortLabel: String) {
    AUTO("Auto", "Auto"),
    ON("On", "On"),
    OFF("Off", "Off")
}

enum class BacklightMode(val label: String, val badge: String) {
    AUTO("Auto", "Backlight Auto"),
    ON("On", "Backlight On"),
    OFF("Off", "Backlight Off"),
}

enum class PixelExtensionMode(val label: String, val badge: String) {
    NONE("Off", "Off"),
    AUTO("Auto", "Pixel Auto"),
    HDR("HDR", "Pixel HDR"),
    NIGHT("Night", "Pixel Night"),
}

enum class GridOverlayType(val label: String) {
    OFF("Off"),
    RULE_OF_THIRDS("3 × 3"),
    GOLDEN_RATIO("Golden"),
    SQUARE_4X4("4 × 4")
}

enum class AspectRatioMode(val label: String, val ratioValue: Float) {
    RATIO_4_3("4:3", 4f / 3f),
    RATIO_16_9("16:9", 16f / 9f)
}

enum class FlashMode(val label: String) {
    OFF("Off"),
    AUTO("Auto"),
    ON("On")
}

enum class TimerDuration(val label: String, val seconds: Int) {
    OFF("Off", 0),
    SEC_3("3s", 3),
    SEC_10("10s", 10)
}

enum class ProDialTab(val label: String, val shortLabel: String) {
    EV("Exposure", "EV"),
    SHADOWS("Shadows", "SHD"),
    HIGHLIGHTS("Highlights", "HIL"),
    CONTRAST("Contrast", "CTR"),
    VIBRANCY("Vibrancy", "VIB"),
    WHITE_BALANCE("White Bal", "WB"),
    ISO("ISO", "ISO"),
    SHUTTER("Shutter", "S"),
    FOCUS("Focus", "MF")
}

@Immutable
data class TonePreset(
    val id: String,
    val name: String,
    val shadowBoost: Float,
    val highlightBoost: Float,
    val contrastAdjust: Float,
    val vibrancyAdjust: Float,
    val createdAtMs: Long = 0L,
)

object ToneColorMath {
    const val CINEMATIC_EV_BIAS = -0.11f
    const val CINEMATIC_SHADOW_OFFSET = -0.10f
    const val CINEMATIC_CONTRAST_OFFSET = 0.24f
    const val CINEMATIC_VIBRANCY_OFFSET = 0.18f

    /**
     * Builds an Android ColorMatrix combining live computational EV compensation,
     * Shadows, Highlights, Contrast, Vibrancy, optional Cinematic Effect, and Kelvin WB
     * for Android 12 fallback preview rendering and ColorMatrix grading.
     */
    fun buildPreviewColorMatrix(
        computationalEvDelta: Float = 0f,
        shadowSlider: Float = 0f,
        highlightSlider: Float = 0f,
        contrastSlider: Float = 0f,
        vibrancySlider: Float = 0f,
        kelvinOverride: Int? = null,
        cinematicEffect: Boolean = false,
    ): ColorMatrix {
        val effShadow = (shadowSlider + if (cinematicEffect) CINEMATIC_SHADOW_OFFSET else 0f).coerceIn(-1f, 1f)
        val effContrast = (contrastSlider + if (cinematicEffect) CINEMATIC_CONTRAST_OFFSET else 0f).coerceIn(-1f, 1f)
        val effVibrancy = (vibrancySlider + if (cinematicEffect) CINEMATIC_VIBRANCY_OFFSET else 0f).coerceIn(-1f, 1f)
        val effEvBias = if (cinematicEffect) CINEMATIC_EV_BIAS else 0f

        val c = (1f + effContrast * 0.45f).coerceIn(0.45f, 1.85f)
        val sat = (1f + effVibrancy * 0.75f).coerceIn(0f, 2.5f)

        val kelvinDelta = if (kelvinOverride != null) {
            ((kelvinOverride - 5400f) / 3000f).coerceIn(-0.6f, 0.6f)
        } else 0f

        val effWarmth = kelvinDelta * 0.28f
        val evGain = 2f.pow((computationalEvDelta + highlightSlider * 0.28f).coerceIn(-2.5f, 2.5f) * 0.42f + effEvBias)
        val lift = (effShadow * 0.20f - highlightSlider * 0.06f) * 42f

        val result = ColorMatrix()
        val satMatrix = ColorMatrix().apply { setSaturation(sat) }
        result.postConcat(satMatrix)

        val translate = 128f * (1f - c) + lift
        val rGain = evGain * c * (1f + effWarmth * 0.25f)
        val gGain = evGain * c
        val bGain = evGain * c * (1f - effWarmth * 0.28f)

        val toneMatrix = ColorMatrix(
            floatArrayOf(
                rGain, 0f, 0f, 0f, translate,
                0f, gGain, 0f, 0f, translate,
                0f, 0f, bGain, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        result.postConcat(toneMatrix)
        return result
    }
}

object ToneShaderMath {
    const val INPUT_SHADER_UNIFORM = "inputShader"

    /**
     * Ultra-fast FP16 AGSL shader (Android 13+ / API 33+) reproducing HdrPlusEngine's non-linear
     * shadow lift, highlight roll-off curve, saturation-aware vibrancy, contrast, EV gain, and
     * Cinematic look using branch-light FP16 polynomial math (zero transcendental pow() calls)
     * so RenderThread stays completely fluid alongside 120fps UI animations.
     */
    const val AGSL_TONE_SHADER = """
        uniform shader inputShader;
        uniform float uShadowLift;
        uniform float uHighlightRecover;
        uniform float uHighlightAdjust;
        uniform float uContrast;
        uniform float uVibrancy;
        uniform float3 uEvGain;

        half4 main(float2 fragCoord) {
            half4 c = inputShader.eval(fragCoord);
            half3 rgb = clamp(c.rgb, half3(0.0), half3(1.0));
            half luma = dot(rgb, half3(0.299, 0.587, 0.114));

            half v = luma;
            half shadowLift = half(uShadowLift);
            if (shadowLift > 0.001h) {
                half omp = 1.0h - v;
                half omp2 = omp * omp;
                half omp4 = omp2 * omp2;
                half w = omp2 * (1.0h - omp4);
                v += shadowLift * w * 0.55h;
            } else if (shadowLift < -0.001h) {
                half omp = 1.0h - v;
                half w = (omp * omp) * v;
                v += shadowLift * w * 1.15h;
            }

            half hlRec = half(uHighlightRecover);
            if (hlRec > 0.01h && v > 0.45h) {
                half excess = (v - 0.45h) * 1.8182h;
                half compressed = 0.45h + 0.55h * (excess / (1.0h + excess * hlRec * 0.85h));
                v = mix(v, compressed, hlRec * 0.55h);
            }

            half hlAdj = half(uHighlightAdjust);
            if (abs(hlAdj) > 0.005h) {
                half hw = (v * v) * (1.0h - v * 0.35h);
                v += hlAdj * hw * 0.40h;
            }

            half targetLuma = clamp(v, 0.0h, 1.0h);
            half3 graded = (luma > 0.004h)
                ? (rgb * (targetLuma / luma))
                : (rgb + half3(targetLuma - luma));

            half vib = half(uVibrancy);
            if (abs(vib) > 0.01h) {
                half maxC = max(graded.r, max(graded.g, graded.b));
                half minC = min(graded.r, min(graded.g, graded.b));
                half sat = (maxC > 0.001h) ? ((maxC - minC) / maxC) : 0.0h;
                half vibScale = clamp(1.0h + vib * (1.0h - sat * 0.45h) * 0.42h, 0.25h, 1.85h);
                graded = half3(targetLuma) + (graded - half3(targetLuma)) * vibScale;
            }

            graded = clamp(graded, half3(0.0), half3(1.0));
            graded = (graded - 0.5h) * half(uContrast) + 0.5h;
            graded *= half3(uEvGain);
            return half4(clamp(graded, half3(0.0), half3(1.0)), c.a);
        }
    """

    fun isNeutral(
        manual: ManualExposureSettings,
        hdrMode: HdrMode,
        config: HdrPipelineConfig,
        hardwareManualSupported: Boolean,
    ): Boolean {
        return !manual.isAnyToneOverride &&
            manual.exposureCompensationEv == 0f &&
            (hardwareManualSupported || manual.whiteBalanceKelvin == null) &&
            !config.cinematicEffectEnabled &&
            hdrMode == HdrMode.OFF
    }

    /**
     * Returns true ONLY when non-linear per-pixel curve adjustments (Shadows or Highlights sliders)
     * are actively adjusted by the user. Standard Cinematic, Contrast, Vibrancy, EV, and WB grading
     * runs directly inside TextureView's hardware layer paint with ZERO offscreen FBO allocation
     * so UI overlays stay locked at 120fps.
     */
    fun needsNonLinearShader(manual: ManualExposureSettings): Boolean {
        return abs(manual.shadowBoost) > 0.01f || abs(manual.highlightBoost) > 0.01f
    }

    fun applyUniforms(
        shader: android.graphics.RuntimeShader,
        manual: ManualExposureSettings,
        hdrMode: HdrMode,
        config: HdrPipelineConfig,
        hardwareManualSupported: Boolean,
    ) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val hdrStrength = when (hdrMode) {
            HdrMode.OFF -> 0f
            HdrMode.HDR_PLUS -> 0.35f
            HdrMode.HDR_PLUS_ENHANCED -> 0.55f
        }
        val effShadow = (manual.shadowBoost + if (config.cinematicEffectEnabled) ToneColorMath.CINEMATIC_SHADOW_OFFSET else 0f)
            .coerceIn(-1f, 1f)
        val shadowLift = (effShadow * 0.45f).coerceIn(-0.55f, 0.75f)
        val highlightRecover = (config.highlightRecovery * hdrStrength * 0.65f).coerceIn(0f, 1f)
        val effContrast = (manual.contrastAdjust + if (config.cinematicEffectEnabled) ToneColorMath.CINEMATIC_CONTRAST_OFFSET else 0f)
            .coerceIn(-1f, 1f)
        val effVibrancy = (manual.vibrancyAdjust + if (config.cinematicEffectEnabled) ToneColorMath.CINEMATIC_VIBRANCY_OFFSET else 0f)
            .coerceIn(-1f, 1f)
        val contrastSlope = (1f + effContrast * 0.45f).coerceIn(0.45f, 1.85f)

        val evVisualDelta = if (manual.isManualExposureActive) {
            manual.exposureCompensationEv * 0.70f
        } else {
            manual.exposureCompensationEv * 0.25f
        }
        val cinematicBias = if (config.cinematicEffectEnabled) ToneColorMath.CINEMATIC_EV_BIAS else 0f
        val evGain = 2f.pow((evVisualDelta * 0.42f) + cinematicBias)

        val (wbR, wbG, wbB) = if (!hardwareManualSupported && manual.whiteBalanceKelvin != null) {
            val kDelta = ((manual.whiteBalanceKelvin - 5400f) / 3000f).coerceIn(-0.6f, 0.6f) * 0.28f
            Triple(1f + kDelta * 0.25f, 1f, 1f - kDelta * 0.28f)
        } else {
            Triple(1f, 1f, 1f)
        }

        shader.setFloatUniform("uShadowLift", shadowLift)
        shader.setFloatUniform("uHighlightRecover", highlightRecover)
        shader.setFloatUniform("uHighlightAdjust", manual.highlightBoost.coerceIn(-1f, 1f))
        shader.setFloatUniform("uContrast", contrastSlope)
        shader.setFloatUniform("uVibrancy", effVibrancy)
        shader.setFloatUniform("uEvGain", evGain * wbR, evGain * wbG, evGain * wbB)
    }
}

@Immutable
data class ManualExposureSettings(
    val exposureCompensationEv: Float = 0f,
    val shadowBoost: Float = 0f,
    val highlightBoost: Float = 0f,
    val contrastAdjust: Float = 0f,
    val vibrancyAdjust: Float = 0f,
    val whiteBalanceKelvin: Int? = null,
    val iso: Int? = null,
    val shutterSpeedNs: Long? = null,
    val focusDistanceDiopter: Float? = null,
) {
    val isManualExposureActive: Boolean
        get() = iso != null || shutterSpeedNs != null

    val isAnyToneOverride: Boolean
        get() = shadowBoost != 0f ||
            highlightBoost != 0f ||
            contrastAdjust != 0f ||
            vibrancyAdjust != 0f

    val isAnyManualOverride: Boolean
        get() = exposureCompensationEv != 0f ||
            isAnyToneOverride ||
            whiteBalanceKelvin != null ||
            iso != null ||
            shutterSpeedNs != null ||
            focusDistanceDiopter != null

    fun formattedShutter(): String {
        val ns = shutterSpeedNs ?: return "Auto"
        val sec = ns / 1_000_000_000.0
        return if (sec >= 0.5) {
            String.format("%.1f\"", sec)
        } else {
            val denom = (1.0 / sec).toInt().coerceAtLeast(1)
            "1/${denom}s"
        }
    }

    fun formattedIso(): String = iso?.toString() ?: "Auto"

    fun formattedWb(): String = whiteBalanceKelvin?.let { "${it}K" } ?: "Auto"

    fun formattedFocus(): String {
        val d = focusDistanceDiopter ?: return "AF"
        return when {
            d <= 0.2f -> "∞"
            else -> String.format("%.2fm", 1f / d)
        }
    }
}

@Immutable
data class HdrPipelineConfig(
    val burstFrameCount: Int = 3,
    val subPixelAlignment: Boolean = true,
    val ultraHdrEnabled: Boolean = true,
    val cinematicEffectEnabled: Boolean = true,
    val highlightRecovery: Float = 0.78f,
    val localToneMapping: Float = 0.65f,
    val temporalDenoise: Float = 0.70f,
    val sabreSharpening: Float = 0.72f,
    val rawDngEnabled: Boolean = false,
    val mirrorFrontCamera: Boolean = true,
    val backlightMode: BacklightMode = BacklightMode.AUTO,
    val backlightDehazeStrength: Float = 0.65f,
    val backlightShadowClarity: Float = 0.55f,
    val faceDetectionEnabled: Boolean = true,
    val faceDeblurEnabled: Boolean = true,
    val motionDeblurStrength: Float = 0.72f,
)

/**
 * Face bounding box normalized to [0f, 1f] in upright preview / captured-image coordinates.
 */
@Immutable
data class DetectedFace(
    val id: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val score: Int = 80,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
    val area: Float get() = width * height

    fun contains(normX: Float, normY: Float, padFraction: Float = 0f): Boolean {
        val padX = width * padFraction
        val padY = height * padFraction
        return normX >= (left - padX) && normX <= (right + padX) &&
            normY >= (top - padY) && normY <= (bottom + padY)
    }
}

/**
 * Pure coordinate transformer mapping Camera2 sensor-space face rectangles into normalized
 * [0f, 1f] upright viewfinder & captured-image coordinates, accounting for digital zoom crop,
 * sensor orientation, aspect-ratio center-crop, and front-camera mirroring.
 */
object FaceCoordinateMapper {
    fun mapSensorRectToNormalizedUpright(
        faceLeft: Int,
        faceTop: Int,
        faceRight: Int,
        faceBottom: Int,
        cropLeft: Int,
        cropTop: Int,
        cropRight: Int,
        cropBottom: Int,
        sensorOrientationDegrees: Int,
        mirrorHorizontally: Boolean,
        targetUprightAspect: Float? = null,
        zoomRatio: Float = 1f,
        faceId: Int = 0,
        score: Int = 80,
    ): DetectedFace? {
        val rawCropW = (cropRight - cropLeft).toFloat()
        val rawCropH = (cropBottom - cropTop).toFloat()
        if (rawCropW <= 1f || rawCropH <= 1f) return null

        // On Android 11+ (API 30+), CameraX digital zoom uses CONTROL_ZOOM_RATIO while keeping
        // SCALER_CROP_REGION equal to the full active array. Apply the effective centered crop.
        val effectiveZoom = zoomRatio.coerceAtLeast(1f)
        val centerX = (cropLeft + cropRight) * 0.5f
        val centerY = (cropTop + cropBottom) * 0.5f
        val cropW = rawCropW / effectiveZoom
        val cropH = rawCropH / effectiveZoom
        val effCropLeft = centerX - cropW * 0.5f
        val effCropTop = centerY - cropH * 0.5f

        val sx1 = ((faceLeft - effCropLeft) / cropW).coerceIn(-0.25f, 1.25f)
        val sy1 = ((faceTop - effCropTop) / cropH).coerceIn(-0.25f, 1.25f)
        val sx2 = ((faceRight - effCropLeft) / cropW).coerceIn(-0.25f, 1.25f)
        val sy2 = ((faceBottom - effCropTop) / cropH).coerceIn(-0.25f, 1.25f)

        val normRotation = ((sensorOrientationDegrees % 360) + 360) % 360
        fun rotateSensorPoint(sx: Float, sy: Float): Pair<Float, Float> {
            val (rx, ry) = when (normRotation) {
                90 -> (1f - sy) to sx
                180 -> (1f - sx) to (1f - sy)
                270 -> sy to (1f - sx)
                else -> sx to sy
            }
            val mx = if (mirrorHorizontally) 1f - rx else rx
            return mx to ry
        }

        val (ux1, uy1) = rotateSensorPoint(sx1, sy1)
        val (ux2, uy2) = rotateSensorPoint(sx2, sy2)

        var left = minOf(ux1, ux2)
        var right = maxOf(ux1, ux2)
        var top = minOf(uy1, uy2)
        var bottom = maxOf(uy1, uy2)

        // Apply FILL_CENTER aspect-ratio crop when preview/output aspect differs from sensor crop aspect
        if (targetUprightAspect != null && targetUprightAspect > 0.1f) {
            val rotated90or270 = normRotation == 90 || normRotation == 270
            val uprightCropAspect = if (rotated90or270) (cropH / cropW) else (cropW / cropH)
            if (uprightCropAspect > 0.1f) {
                if (targetUprightAspect < uprightCropAspect - 0.01f) {
                    val scaleX = uprightCropAspect / targetUprightAspect
                    left = (left - 0.5f) * scaleX + 0.5f
                    right = (right - 0.5f) * scaleX + 0.5f
                } else if (targetUprightAspect > uprightCropAspect + 0.01f) {
                    val scaleY = targetUprightAspect / uprightCropAspect
                    top = (top - 0.5f) * scaleY + 0.5f
                    bottom = (bottom - 0.5f) * scaleY + 0.5f
                }
            }
        }

        val cl = left.coerceIn(0f, 1f)
        val ct = top.coerceIn(0f, 1f)
        val cr = right.coerceIn(0f, 1f)
        val cb = bottom.coerceIn(0f, 1f)
        if ((cr - cl) < 0.025f || (cb - ct) < 0.025f) return null

        return DetectedFace(
            id = faceId,
            left = cl,
            top = ct,
            right = cr,
            bottom = cb,
            score = score.coerceIn(0, 100),
        )
    }

    /**
     * Exponentially smooths detected faces by matching IDs or nearest centroid so viewfinder
     * reticles track smoothly at 60–120fps without jitter.
     */
    fun smoothFaces(
        previous: List<DetectedFace>,
        current: List<DetectedFace>,
        alpha: Float = 0.42f,
    ): List<DetectedFace> {
        if (current.isEmpty()) return emptyList()
        if (previous.isEmpty()) return current.sortedByDescending { it.area }.take(5)
        val clampedAlpha = alpha.coerceIn(0.15f, 1f)
        return current
            .sortedByDescending { it.area }
            .take(5)
            .map { face ->
                val match = previous.firstOrNull { it.id >= 0 && it.id == face.id }
                    ?: previous.minByOrNull { prev ->
                        val dx = prev.centerX - face.centerX
                        val dy = prev.centerY - face.centerY
                        dx * dx + dy * dy
                    }?.takeIf { prev ->
                        val dx = prev.centerX - face.centerX
                        val dy = prev.centerY - face.centerY
                        (dx * dx + dy * dy) <= 0.04f
                    }
                if (match == null) {
                    face
                } else {
                    DetectedFace(
                        id = face.id,
                        left = match.left + (face.left - match.left) * clampedAlpha,
                        top = match.top + (face.top - match.top) * clampedAlpha,
                        right = match.right + (face.right - match.right) * clampedAlpha,
                        bottom = match.bottom + (face.bottom - match.bottom) * clampedAlpha,
                        score = face.score,
                    )
                }
            }
    }
}

@Immutable
data class LensOption(
    val id: String,
    val label: String,
    val zoomRatio: Float,
    val cameraId: String? = null,
    val focalLengthMm35: Int = 24,
    val aperture: Float? = null,
)

@Immutable
data class LiveTelemetry(
    val lumaHistogram: List<Int> = List(32) { 0 },
    val redHistogram: List<Int> = List(32) { 0 },
    val greenHistogram: List<Int> = List(32) { 0 },
    val blueHistogram: List<Int> = List(32) { 0 },
    val meanLuma: Float = 0.5f,
    val clippedHighlightPercent: Float = 0f,
    val crushedShadowPercent: Float = 0f,
    val edgeSharpnessScore: Float = 0f,
    val estimatedIso: Int = 100,
    val estimatedShutterLabel: String = "1/125s",
    val estimatedWbKelvin: Int = 5400,
    val rollDegrees: Float = 0f,
    val pitchDegrees: Float = 0f,
    val isLevelAligned: Boolean = true,
    val rawSupported: Boolean = false,
    val manualControlHardwareSupported: Boolean = false,
    val minEvIndex: Int = -6,
    val maxEvIndex: Int = 6,
    val evStep: Float = 0.33f,
    val isoRange: IntRange = 50..6400,
    val minFocusDiopter: Float = 10f,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 8f,
    val activeFocalLengthMm35: Int = 24,
    val activeAperture: Float? = null,
    val lensOptions: List<LensOption> = listOf(
        LensOption(id = "wide_1x", label = "1x", zoomRatio = 1f, focalLengthMm35 = 24),
        LensOption(id = "tele_2x", label = "2", zoomRatio = 2f, focalLengthMm35 = 48),
    ),
)

@Immutable
data class HdrProcessMetrics(
    val framesMerged: Int,
    val alignmentConfidence: Float,
    val dynamicRangeGainStops: Float,
    val processingTimeMs: Long,
    val highlightRecoveryApplied: Float,
    val shadowLiftApplied: Float,
    val isUltraHdr: Boolean = false,
    val isNightSight: Boolean = false,
    val totalExposureSeconds: Float = 0f,
    val isBacklightEnhanced: Boolean = false,
    val backlightDehazeApplied: Float = 0f,
    val facesDetected: Int = 0,
    val faceDeblurApplied: Boolean = false,
    val motionDeblurApplied: Float = 0f,
)

@Immutable
data class CapturedPhotoRecord(
    val uriString: String,
    val displayName: String,
    val timestampMs: Long,
    val width: Int,
    val height: Int,
    val mode: CameraMode,
    val hdrMode: HdrMode,
    val lookName: String,
    val metrics: HdrProcessMetrics,
    val isoUsed: Int,
    val shutterUsedLabel: String,
    val evUsed: Float,
    val wbKelvinUsed: Int,
    val hasDngRaw: Boolean = false,
    val isUltraHdr: Boolean = false,
    val isNightSight: Boolean = false,
    val isBacklightEnhanced: Boolean = false,
    val facesDetected: Int = 0,
    val isDeblurEnhanced: Boolean = false,
    val lumaHistogram: List<Int> = emptyList(),
)

@Immutable
data class LowLightExposurePlan(
    val perFrameExposureNs: Long,
    val targetIso: Int,
    val frameCount: Int,
    val estimatedTotalHoldMs: Long,
    val evBoostStops: Float,
)

/**
 * Pure low-light detection and Pixel Night Sight long-exposure planner.
 * Combines Camera2 hardware AE telemetry (ISO + exposure time) with live frame luminance
 * so dark rooms are reliably detected even when hardware AE boosts ISO to brighten preview.
 */
object LowLightDetector {
    const val ENTER_LOW_LIGHT_THRESHOLD = 0.48f
    const val EXIT_LOW_LIGHT_THRESHOLD = 0.32f

    /**
     * Computes normalized darkness severity in [0f, 1f]:
     * 0.0 = bright daylight, ~0.50 = dim indoor / dusk, 1.0 = very dark night scene.
     */
    fun computeSeverity(
        meanLuma: Float,
        crushedShadowPercent: Float,
        sensorIso: Int? = null,
        exposureTimeNs: Long? = null,
    ): Float {
        val safeLuma = meanLuma.coerceIn(0f, 1f)
        // Luma-based score: begins rising below 0.34, saturates near 0.08
        val lumaDarkness = ((0.36f - safeLuma) / 0.28f).coerceIn(0f, 1f)
        val shadowFactor = ((crushedShadowPercent - 14f) / 55f).coerceIn(0f, 1f)
        val frameScore = (lumaDarkness * 0.78f + shadowFactor * 0.22f).coerceIn(0f, 1f)

        if (sensorIso != null && sensorIso > 0 && exposureTimeNs != null && exposureTimeNs > 0L) {
            // Exposure load = ISO * shutterSeconds / observedLuma.
            // Bright daylight: 100 * 0.001 / 0.5 = 0.2
            // Normal indoor: 400 * 0.02 / 0.45 = 17.8
            // Dim indoor (low light start): 800 * 0.033 / 0.32 = 82.5
            // Dark room: 1600 * 0.05 / 0.22 = 363
            // Very dark night: 3200 * 0.066 / 0.12 = 1760
            val expSec = exposureTimeNs / 1_000_000_000.0
            val exposureLoad = (sensorIso * expSec) / safeLuma.coerceAtLeast(0.08f)
            val logLoad = ln(exposureLoad.coerceAtLeast(1.0)).toFloat()
            // ln(35) ≈ 3.55 (indoor), ln(75) ≈ 4.32 (dim threshold), ln(850) ≈ 6.75 (very dark)
            val hwScore = ((logLoad - 3.4f) / 3.35f).coerceIn(0f, 1f)
            // Blend hardware telemetry (primary) with frame darkness so pitch-black frames always trigger
            return maxOf(frameScore, hwScore * 0.72f + frameScore * 0.28f).coerceIn(0f, 1f)
        }

        return frameScore
    }

    /**
     * Hysteresis state transition to prevent Night Sight indicator flicker near the light threshold.
     */
    fun nextLowLightActiveState(currentlyActive: Boolean, smoothedSeverity: Float): Boolean {
        return if (currentlyActive) {
            smoothedSeverity >= EXIT_LOW_LIGHT_THRESHOLD
        } else {
            smoothedSeverity >= ENTER_LOW_LIGHT_THRESHOLD
        }
    }

    /**
     * Converts Camera2 preview AE telemetry (`SENSOR_SENSITIVITY` + `CONTROL_POST_RAW_SENSITIVITY_BOOST`)
     * into a conservative baseline sensor ISO suitable for manual `CONTROL_AE_MODE_OFF` capture.
     *
     * On Pixel / Tensor and DRC sensors, `CONTROL_POST_RAW_SENSITIVITY_BOOST` (often 200-1600,
     * especially while Low Light Boost is active) represents ISP dynamic-range tone-mapped digital
     * boost rather than pure linear headroom. Multiplying linearly by the full post-RAW boost
     * drives physical sensor exposure 2-3+ stops too hot when switching to manual long shutter.
     */
    fun computeManualBaselineIso(
        sensorIso: Int,
        postRawSensitivityBoost: Int = 100,
        postRawBoostSupported: Boolean = true,
        lowLightBoostActive: Boolean = false,
    ): Int {
        val safeIso = sensorIso.coerceAtLeast(1)
        if (!postRawBoostSupported || postRawSensitivityBoost <= 100) {
            return safeIso
        }
        val maxFactor = if (lowLightBoostActive) 1.15 else 1.45
        val dampedFactor = kotlin.math.sqrt(postRawSensitivityBoost.coerceIn(100, 1600) / 100.0)
            .coerceIn(1.0, maxFactor)
        return (safeIso * dampedFactor).toInt().coerceAtLeast(safeIso)
    }

    /**
     * Computes handheld Pixel-style long-exposure parameters using exposure equivalence:
     * - Automatically measures how much additional light the scene actually needs based on
     *   current preview luminance (`meanLuma`) and highlight clipping (`clippedHighlightPercent`).
     * - When AE already exposed the scene properly via high noisy ISO, extends shutter speed while
     *   proportionally LOWERING ISO (same brightness, dramatically less noise, zero overexposure).
     * - When the scene is genuinely underexposed (`meanLuma < 0.28`), boosts total exposure
     *   conservatively to preserve sensor highlight headroom, while capping shutter time so
     *   `isoRange.first` never forces overexposure.
     */
    fun planLongExposure(
        severity: Float,
        meanLuma: Float,
        currentIso: Int?,
        currentExposureNs: Long?,
        isoRange: IntRange,
        exposureTimeRangeNs: LongRange,
        clippedHighlightPercent: Float = 0f,
        manualEvCompensation: Float = 0f,
    ): LowLightExposurePlan {
        val clampedSeverity = severity.coerceIn(0.15f, 1f)
        val safeLuma = meanLuma.coerceIn(0.05f, 0.95f)
        // Balanced handheld frame count (3-5): captures +1.0 to +1.2 stops SNR while keeping total
        // hold time under ~2.5s and halving CPU burst-merge latency compared to 8-frame stacks.
        val frameCount = when {
            clampedSeverity >= 0.78f -> 5
            clampedSeverity >= 0.48f -> 4
            else -> 3
        }

        // Target natural night luminance (~0.28 sRGB): preserves deep night ambience and highlight
        // headroom on the sensor so multi-frame stacking + soft shadow lift never blows out lights.
        val targetLuma = 0.28f
        val rawLinearGain = if (safeLuma >= targetLuma) {
            // Scene already has enough brightness — use longer shutter ONLY to drop ISO & reduce noise!
            1.0f
        } else {
            // Conservative linear boost (max +0.81 EV) so bright lamps/walls never hard-clip on sensor
            (targetLuma / safeLuma).pow(1.5f).coerceIn(1.0f, 1.75f)
        }

        // Protect bright lamps / windows / screens in the frame from blowing out
        val highlightDamp = if (clippedHighlightPercent > 0.5f) {
            (1f - ((clippedHighlightPercent - 0.5f) / 6.5f)).coerceIn(0.05f, 1f)
        } else {
            1.0f
        }
        val clipPullback = if (clippedHighlightPercent > 6f) {
            (1f - ((clippedHighlightPercent - 6f) / 30f)).coerceIn(0.65f, 1f)
        } else {
            1.0f
        }
        val desiredLinearGain = ((1.0f + (rawLinearGain - 1.0f) * highlightDamp) * clipPullback)
            .coerceIn(0.65f, 1.75f)

        val baseIso = (currentIso ?: ((500f + clampedSeverity * 1500f).toInt()))
            .coerceIn(isoRange.first, isoRange.last)
        val baseExpNs = (currentExposureNs ?: 33_333_333L).coerceAtLeast(500_000L)
        val userEvScale = if (manualEvCompensation != 0f) {
            2f.pow(manualEvCompensation.coerceIn(-2.5f, 2.5f)).toDouble()
        } else {
            1.0
        }

        // Desired total sensor exposure product (ISO * ns)
        val desiredProduct = baseIso.toDouble() * baseExpNs.toDouble() * desiredLinearGain.toDouble() * userEvScale

        // Preferred handheld long shutter (100ms in dim light up to 280ms in pitch dark)
        val minIso = isoRange.first.coerceAtLeast(25)
        val preferredExposureNs = ((100f + clampedSeverity * 180f).toLong() * 1_000_000L)
        // CRITICAL: Never hold shutter longer than what minIso can support without overexposing!
        val maxSafeExposureNs = (desiredProduct / minIso.toDouble()).toLong()
            .coerceAtLeast(exposureTimeRangeNs.first)

        val desiredExposureNs = minOf(preferredExposureNs, maxSafeExposureNs)
            .coerceIn(
                exposureTimeRangeNs.first.coerceAtLeast(1_000_000L),
                exposureTimeRangeNs.last.coerceAtMost(280_000_000L),
            )

        // Solve exact ISO for the chosen shutter time to hit `desiredProduct` accurately
        val idealIso = (desiredProduct / desiredExposureNs.toDouble()).toInt()
        val maxNightIso = isoRange.last.coerceAtMost(3200).coerceAtLeast(minIso)
        val targetIso = idealIso.coerceIn(minIso, maxNightIso)

        val actualProduct = targetIso.toDouble() * desiredExposureNs.toDouble()
        val effectiveGain = (actualProduct / (baseIso.toDouble() * baseExpNs.toDouble())).coerceAtLeast(0.25)
        val evBoostStops = (ln(effectiveGain) / ln(2.0)).toFloat().coerceIn(-1.5f, 1.0f)

        // Account for per-frame exposure + fast MINIMIZE_LATENCY readout overhead (~90ms per frame)
        val perFrameCycleMs = (desiredExposureNs / 1_000_000L) + 90L
        val totalHoldMs = (perFrameCycleMs * frameCount).coerceIn(700L, 2400L)

        return LowLightExposurePlan(
            perFrameExposureNs = desiredExposureNs,
            targetIso = targetIso,
            frameCount = frameCount,
            estimatedTotalHoldMs = totalHoldMs,
            evBoostStops = evBoostStops,
        )
    }

    fun formatExposureSeconds(totalSeconds: Float): String {
        return if (totalSeconds >= 1.0f) {
            String.format("%.1fs", totalSeconds)
        } else {
            "${(totalSeconds * 1000f).toInt()}ms"
        }
    }

    /**
     * Detects strongly backlit / high-dynamic-range scenes where a bright light behind the subject
     * simultaneously clips highlights while leaving the subject's front shadows crushed — OR when
     * a detected face is significantly darker than the surrounding scene/highlights.
     */
    fun isBacklitScene(
        clippedHighlightPercent: Float,
        crushedShadowPercent: Float,
        meanLuma: Float,
        faceMeanLuma: Float? = null,
    ): Boolean {
        val strongSplit = clippedHighlightPercent >= 3.5f && crushedShadowPercent >= 16f
        val extremeBacklight = clippedHighlightPercent >= 6.5f && crushedShadowPercent >= 10f && meanLuma < 0.62f
        val backlitFace = if (faceMeanLuma != null) {
            val faceMuchDarkerThanBg = faceMeanLuma < 0.38f && (meanLuma - faceMeanLuma) >= 0.16f
            val darkFaceWithBrightBg = faceMeanLuma < 0.42f && clippedHighlightPercent >= 1.8f
            faceMuchDarkerThanBg || darkFaceWithBrightBg
        } else false
        return strongSplit || extremeBacklight || backlitFace
    }
}

object KelvinColorMath {
    fun kelvinToRgbGains(kelvin: Int): Triple<Float, Float, Float> {
        val temp = kelvin.coerceIn(2000, 10000) / 100.0
        val r: Double
        val g: Double
        val b: Double

        if (temp <= 66.0) {
            r = 255.0
            g = (99.4708025861 * ln(temp) - 161.1195681661).coerceIn(0.0, 255.0)
            b = if (temp <= 19.0) {
                0.0
            } else {
                (138.5177312231 * ln(temp - 10.0) - 305.0447927307).coerceIn(0.0, 255.0)
            }
        } else {
            r = (329.698727446 * (temp - 60.0).pow(-0.1332047592)).coerceIn(0.0, 255.0)
            g = (288.1221695283 * (temp - 60.0).pow(-0.0755148492)).coerceIn(0.0, 255.0)
            b = 255.0
        }

        val gSafe = (g / 255.0).coerceAtLeast(0.2)
        val rGain = ((r / 255.0) / gSafe).toFloat().coerceIn(0.55f, 2.2f)
        val bGain = ((b / 255.0) / gSafe).toFloat().coerceIn(0.55f, 2.2f)
        return Triple(rGain, 1.0f, bGain)
    }
}
