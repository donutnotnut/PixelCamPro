package com.example

import android.graphics.Bitmap
import android.graphics.Color
import com.example.camera.model.CameraMode
import com.example.camera.model.DetectedFace
import com.example.camera.model.FaceCoordinateMapper
import com.example.camera.model.HdrMode
import com.example.camera.model.HdrPipelineConfig
import com.example.camera.model.LowLightDetector
import com.example.camera.model.ManualExposureSettings
import com.example.camera.processing.HdrPlusEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FaceAndNightSightTest {

    @Test
    fun faceCoordinateMapper_appliesZoomRatioAroundCenter() {
        // Sensor active array 4000x3000, face centered at (2500, 1500) with size 400x400
        // At 1x (rotation 0): centerX = 2500/4000 = 0.625, width = 400/4000 = 0.10
        val face1x = FaceCoordinateMapper.mapSensorRectToNormalizedUpright(
            faceLeft = 2300,
            faceTop = 1300,
            faceRight = 2700,
            faceBottom = 1700,
            cropLeft = 0,
            cropTop = 0,
            cropRight = 4000,
            cropBottom = 3000,
            sensorOrientationDegrees = 0,
            mirrorHorizontally = false,
            zoomRatio = 1f,
        )
        assertNotNull(face1x)
        assertEquals(0.625f, face1x!!.centerX, 0.005f)
        assertEquals(0.10f, face1x.width, 0.005f)

        // At 2x zoom (SCALER_CROP_REGION still 4000x3000 on API 30+), offset from center doubles & width doubles:
        // centerX = 0.5 + (0.625 - 0.5) * 2 = 0.75, width = 0.20
        val face2x = FaceCoordinateMapper.mapSensorRectToNormalizedUpright(
            faceLeft = 2300,
            faceTop = 1300,
            faceRight = 2700,
            faceBottom = 1700,
            cropLeft = 0,
            cropTop = 0,
            cropRight = 4000,
            cropBottom = 3000,
            sensorOrientationDegrees = 0,
            mirrorHorizontally = false,
            zoomRatio = 2f,
        )
        assertNotNull(face2x)
        assertEquals(0.75f, face2x!!.centerX, 0.005f)
        assertEquals(0.20f, face2x.width, 0.005f)
    }

    @Test
    fun faceCoordinateMapper_supportsLandscapeAndPortraitRotations() {
        // Face in top-left quadrant of sensor: [200..800, 200..800] inside 4000x3000
        val landscape = FaceCoordinateMapper.mapSensorRectToNormalizedUpright(
            faceLeft = 200,
            faceTop = 200,
            faceRight = 800,
            faceBottom = 800,
            cropLeft = 0,
            cropTop = 0,
            cropRight = 4000,
            cropBottom = 3000,
            sensorOrientationDegrees = 0,
            mirrorHorizontally = false,
            targetUprightAspect = 4f / 3f,
        )
        assertNotNull(landscape)
        assertEquals(0.125f, landscape!!.centerX, 0.005f)
        assertEquals(0.166f, landscape.centerY, 0.005f)

        // Rotated 90 deg CW (portrait rear): x' = 1 - sy, y' = sx
        val portrait = FaceCoordinateMapper.mapSensorRectToNormalizedUpright(
            faceLeft = 200,
            faceTop = 200,
            faceRight = 800,
            faceBottom = 800,
            cropLeft = 0,
            cropTop = 0,
            cropRight = 4000,
            cropBottom = 3000,
            sensorOrientationDegrees = 90,
            mirrorHorizontally = false,
            targetUprightAspect = 3f / 4f,
        )
        assertNotNull(portrait)
        assertEquals(1f - 0.166f, portrait!!.centerX, 0.01f)
        assertEquals(0.125f, portrait.centerY, 0.01f)
    }

    @Test
    fun nightSightPlanner_staysCompactAndFastForHandheldCapture() {
        val plan = LowLightDetector.planLongExposure(
            severity = 0.92f,
            meanLuma = 0.09f,
            currentIso = 2400,
            currentExposureNs = 50_000_000L,
            isoRange = 50..6400,
            exposureTimeRangeNs = 100_000L..1_000_000_000L,
        )
        assertTrue("Frame count should be compact (3..5), got ${plan.frameCount}", plan.frameCount in 3..5)
        assertTrue("Per-frame shutter should stay <= 280ms handheld, got ${plan.perFrameExposureNs}", plan.perFrameExposureNs <= 280_000_000L)
        assertTrue("Estimated hold should stay <= 2.5s, got ${plan.estimatedTotalHoldMs}ms", plan.estimatedTotalHoldMs <= 2500L)
    }

    @Test
    fun hdrPlusEngine_skipsDeblurOnSharpImageAndAppliesOnBlurredFaceOnly() = runTest {
        val engine = HdrPlusEngine()
        val w = 160
        val h = 160

        // 1. Razor-sharp checkerboard-like vertical/horizontal step bars: deblur should NOT trigger
        val sharpBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = if (((x / 8) + (y / 8)) % 2 == 0) 220 else 35
                sharpBmp.setPixel(x, y, Color.rgb(c, c, c))
            }
        }
        val face = DetectedFace(id = 1, left = 0.30f, top = 0.30f, right = 0.70f, bottom = 0.70f, score = 95)
        val sharpRes = engine.processBurst(
            frames = listOf(sharpBmp),
            mode = CameraMode.CAMERA,
            hdrMode = HdrMode.HDR_PLUS,
            manual = ManualExposureSettings(),
            config = HdrPipelineConfig(
                ultraHdrEnabled = false,
                faceDetectionEnabled = true,
                faceDeblurEnabled = true,
                motionDeblurStrength = 0.75f,
            ),
            detectedFaces = listOf(face),
        )
        assertFalse("Sharp portrait should skip Face Deblur", sharpRes.metrics.faceDeblurApplied)
        assertEquals(0f, sharpRes.metrics.motionDeblurApplied, 0.001f)
        sharpBmp.recycle()
        sharpRes.bitmap.recycle()

        // 2. Sharp background with horizontally motion-blurred face region: should trigger face-only deblur
        val blurredFaceBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val raw = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = if (((x / 8) + (y / 8)) % 2 == 0) 220 else 35
                raw[y * w + x] = c
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val inFace = x in 42..118 && y in 42..118
                val v = if (inFace) {
                    var sum = 0
                    for (dx in -4..4) {
                        sum += raw[y * w + (x + dx).coerceIn(0, w - 1)]
                    }
                    sum / 9
                } else {
                    raw[y * w + x]
                }
                blurredFaceBmp.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        val blurRes = engine.processBurst(
            frames = listOf(blurredFaceBmp),
            mode = CameraMode.CAMERA,
            hdrMode = HdrMode.HDR_PLUS,
            manual = ManualExposureSettings(),
            config = HdrPipelineConfig(
                ultraHdrEnabled = false,
                faceDetectionEnabled = true,
                faceDeblurEnabled = true,
                motionDeblurStrength = 0.80f,
            ),
            detectedFaces = listOf(face),
        )
        assertTrue("Blurred face should trigger Face Deblur", blurRes.metrics.faceDeblurApplied)
        assertTrue("Applied motion deblur strength should be > 0.15", blurRes.metrics.motionDeblurApplied > 0.15f)
        blurredFaceBmp.recycle()
        blurRes.bitmap.recycle()
    }
}
