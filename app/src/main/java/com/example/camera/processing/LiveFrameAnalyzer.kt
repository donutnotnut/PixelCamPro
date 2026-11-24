package com.example.camera.processing

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.Immutable
import kotlin.math.abs

@Immutable
data class LiveFrameAnalysisResult(
    val lumaHistogram: List<Int> = List(32) { 0 },
    val redHistogram: List<Int> = List(32) { 0 },
    val greenHistogram: List<Int> = List(32) { 0 },
    val blueHistogram: List<Int> = List(32) { 0 },
    val meanLuma: Float = 0.5f,
    val clippedHighlightPercent: Float = 0f,
    val crushedShadowPercent: Float = 0f,
    val edgeSharpnessScore: Float = 0f,
)

/**
 * Throttled CameraX ImageAnalysis.Analyzer extracting real-time 32-bin Luma + RGB histograms,
 * highlight clipping, shadow crushing, and edge sharpness metrics directly from YUV_420_888 planes.
 * Immediately drops frames when paused (Settings/Gallery open) and skips RGB math when hidden.
 */
class LiveFrameAnalyzer(
    private val isHistogramVisible: () -> Boolean = { true },
    private val isPaused: () -> Boolean = { false },
    private val onAnalyzed: (LiveFrameAnalysisResult) -> Unit,
) : ImageAnalysis.Analyzer {

    private var lastAnalyzedTimestampMs = 0L
    private val lumaBins = IntArray(32)
    private val rBins = IntArray(32)
    private val gBins = IntArray(32)
    private val bBins = IntArray(32)

    override fun analyze(image: ImageProxy) {
        try {
            if (isPaused()) return
            val needHistogram = isHistogramVisible()
            val minIntervalMs = if (needHistogram) 200L else 600L
            val now = System.currentTimeMillis()
            if (now - lastAnalyzedTimestampMs < minIntervalMs) {
                return
            }
            lastAnalyzedTimestampMs = now

            val planes = image.planes
            if (planes.isEmpty()) return

            val yPlane = planes[0]
            val yBuffer = yPlane.buffer
            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            val yLimit = yBuffer.limit()

            val width = image.width
            val height = image.height

            // Ultra-cheap luma + shadow-crush + highlight-clip fast path when histogram overlay is hidden
            if (!needHistogram) {
                val coarseStep = (minOf(width, height) / 16).coerceAtLeast(8)
                var sumLuma = 0L
                var clippedCount = 0
                var crushedCount = 0
                var count = 0
                for (y in 0 until height step coarseStep) {
                    val rowOff = y * yRowStride
                    for (x in 0 until width step coarseStep) {
                        val idx = rowOff + x * yPixelStride
                        if (idx < yLimit) {
                            val yVal = yBuffer.get(idx).toInt() and 0xFF
                            sumLuma += yVal
                            if (yVal >= 248) clippedCount++
                            if (yVal <= 12) crushedCount++
                            count++
                        }
                    }
                }
                if (count > 0) {
                    onAnalyzed(
                        LiveFrameAnalysisResult(
                            meanLuma = (sumLuma.toFloat() / (count * 255f)).coerceIn(0f, 1f),
                            clippedHighlightPercent = (clippedCount * 100f / count).coerceIn(0f, 100f),
                            crushedShadowPercent = (crushedCount * 100f / count).coerceIn(0f, 100f),
                        )
                    )
                }
                return
            }

            val hasChroma = planes.size >= 3
            val uPlane = if (hasChroma) planes[1] else null
            val vPlane = if (hasChroma) planes[2] else null
            val uBuffer = uPlane?.buffer
            val vBuffer = vPlane?.buffer
            val uRowStride = uPlane?.rowStride ?: 0
            val uPixelStride = uPlane?.pixelStride ?: 1
            val vRowStride = vPlane?.rowStride ?: 0
            val vPixelStride = vPlane?.pixelStride ?: 1
            val uLimit = uBuffer?.limit() ?: 0
            val vLimit = vBuffer?.limit() ?: 0

            val step = (minOf(width, height) / 48).coerceIn(4, 12)

            lumaBins.fill(0)
            rBins.fill(0)
            gBins.fill(0)
            bBins.fill(0)

            var sumLuma = 0L
            var clippedCount = 0
            var crushedCount = 0
            var sampleCount = 0
            var sharpnessSum = 0L
            var prevY = 128

            for (y in 0 until height step step) {
                val yRowOff = y * yRowStride
                val uvRow = y shr 1
                val uRowOff = uvRow * uRowStride
                val vRowOff = uvRow * vRowStride
                for (x in 0 until width step step) {
                    val yIndex = yRowOff + x * yPixelStride
                    if (yIndex >= yLimit) continue
                    val yVal = yBuffer.get(yIndex).toInt() and 0xFF

                    lumaBins[yVal shr 3]++
                    sumLuma += yVal
                    if (yVal >= 248) clippedCount++
                    if (yVal <= 8) crushedCount++
                    sharpnessSum += abs(yVal - prevY)
                    prevY = yVal
                    sampleCount++

                    if (uBuffer != null && vBuffer != null) {
                        val uvCol = x shr 1
                        val uIdx = uRowOff + uvCol * uPixelStride
                        val vIdx = vRowOff + uvCol * vPixelStride
                        if (uIdx < uLimit && vIdx < vLimit) {
                            val u = (uBuffer.get(uIdx).toInt() and 0xFF) - 128
                            val v = (vBuffer.get(vIdx).toInt() and 0xFF) - 128
                            val r = (yVal + ((359 * v) shr 8)).coerceIn(0, 255)
                            val g = (yVal - ((88 * u + 183 * v) shr 8)).coerceIn(0, 255)
                            val b = (yVal + ((454 * u) shr 8)).coerceIn(0, 255)
                            rBins[r shr 3]++
                            gBins[g shr 3]++
                            bBins[b shr 3]++
                        }
                    } else {
                        val bin = yVal shr 3
                        rBins[bin]++
                        gBins[bin]++
                        bBins[bin]++
                    }
                }
            }

            if (sampleCount == 0) return
            val maxBin = lumaBins.maxOrNull()?.coerceAtLeast(1) ?: 1
            val maxR = rBins.maxOrNull()?.coerceAtLeast(1) ?: 1
            val maxG = gBins.maxOrNull()?.coerceAtLeast(1) ?: 1
            val maxB = bBins.maxOrNull()?.coerceAtLeast(1) ?: 1

            onAnalyzed(
                LiveFrameAnalysisResult(
                    lumaHistogram = List(32) { i -> ((lumaBins[i] * 100) / maxBin).coerceIn(0, 100) },
                    redHistogram = List(32) { i -> ((rBins[i] * 100) / maxR).coerceIn(0, 100) },
                    greenHistogram = List(32) { i -> ((gBins[i] * 100) / maxG).coerceIn(0, 100) },
                    blueHistogram = List(32) { i -> ((bBins[i] * 100) / maxB).coerceIn(0, 100) },
                    meanLuma = (sumLuma.toFloat() / (sampleCount * 255f)).coerceIn(0f, 1f),
                    clippedHighlightPercent = (clippedCount * 100f / sampleCount).coerceIn(0f, 100f),
                    crushedShadowPercent = (crushedCount * 100f / sampleCount).coerceIn(0f, 100f),
                    edgeSharpnessScore = (sharpnessSum.toFloat() / sampleCount).coerceIn(0f, 100f),
                )
            )
        } finally {
            image.close()
        }
    }
}
