package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.camera.model.CameraMode
import com.example.camera.model.HdrMode
import com.example.camera.model.HdrPipelineConfig
import com.example.camera.model.KelvinColorMath
import com.example.camera.model.ManualExposureSettings
import com.example.camera.model.ToneShaderMath
import com.example.camera.processing.HdrPlusEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("PixelCam Pro", appName)
    }

    @Test
    fun `kelvin math warms reds at low temp and cools blues at high temp`() {
        val (rWarm, _, bWarm) = KelvinColorMath.kelvinToRgbGains(2800)
        val (rCool, _, bCool) = KelvinColorMath.kelvinToRgbGains(8000)
        assertTrue(rWarm > bWarm)
        assertTrue(bCool > rCool)
    }

    @Test
    fun `hdr plus engine merges burst frames and boosts dynamic range`() = runTest {
        val engine = HdrPlusEngine()
        val frames = List(3) { idx ->
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                for (y in 0 until 64) {
                    for (x in 0 until 64) {
                        val v = if (x < 32) (25 + idx * 10) else (210 + idx * 5).coerceAtMost(255)
                        setPixel(x, y, Color.rgb(v, v, v))
                    }
                }
            }
        }

        val result = engine.processBurst(
            frames = frames,
            mode = CameraMode.CAMERA,
            hdrMode = HdrMode.HDR_PLUS_ENHANCED,
            manual = ManualExposureSettings(
                shadowBoost = 0.4f,
                highlightBoost = -0.3f,
                contrastAdjust = 0.2f,
                vibrancyAdjust = 0.3f,
            ),
            config = HdrPipelineConfig(),
        )

        assertEquals(3, result.metrics.framesMerged)
        assertTrue(result.metrics.dynamicRangeGainStops > 1.0f)
        assertEquals(32, result.lumaHistogram.size)
        frames.forEach { it.recycle() }
        result.bitmap.recycle()
    }

    @Test
    fun `smooth gradient stays free of square tile step artifacts`() = runTest {
        val engine = HdrPlusEngine()
        val w = 256
        val h = 192
        val gradientFrame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val v = ((x.toFloat() / (w - 1)) * 220f + 15f).toInt().coerceIn(0, 255)
                    setPixel(x, y, Color.rgb(v, v, v))
                }
            }
        }

        val result = engine.processBurst(
            frames = listOf(gradientFrame),
            mode = CameraMode.CAMERA,
            hdrMode = HdrMode.HDR_PLUS_ENHANCED,
            manual = ManualExposureSettings(),
            config = HdrPipelineConfig(localToneMapping = 0.8f),
        )

        val midY = h / 2
        var maxNeighborJump = 0
        for (x in 1 until w) {
            val prev = Color.red(result.bitmap.getPixel(x - 1, midY))
            val curr = Color.red(result.bitmap.getPixel(x, midY))
            val jump = abs(curr - prev)
            if (jump > maxNeighborJump) maxNeighborJump = jump
        }

        // Neighboring pixel steps across a smooth gradient must stay tiny (no 16px block jumps)
        assertTrue("Expected smooth gradient without block jumps, got max jump $maxNeighborJump", maxNeighborJump <= 4)
        gradientFrame.recycle()
        result.bitmap.recycle()
    }

    @Test
    fun `bright specular spot produces no black outline halo in hdr modes`() = runTest {
        val engine = HdrPlusEngine()
        val w = 160
        val h = 160
        val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val dx = x - 80
                    val dy = y - 80
                    val v = if (dx * dx + dy * dy <= 14 * 14) 255 else 96
                    setPixel(x, y, Color.rgb(v, v, v))
                }
            }
        }

        for (hdr in listOf(HdrMode.HDR_PLUS, HdrMode.HDR_PLUS_ENHANCED)) {
            val result = engine.processBurst(
                frames = listOf(frame),
                mode = CameraMode.CAMERA,
                hdrMode = hdr,
                manual = ManualExposureSettings(),
                config = HdrPipelineConfig(localToneMapping = 0.8f, sabreSharpening = 0.7f),
            )

            val farBgLuma = Color.red(result.bitmap.getPixel(10, 80))
            var minRingLuma = 255
            for (x in 55..105) {
                for (y in 55..105) {
                    val dx = x - 80
                    val dy = y - 80
                    if (dx * dx + dy * dy > 15 * 15) {
                        val luma = Color.red(result.bitmap.getPixel(x, y))
                        if (luma < minRingLuma) minRingLuma = luma
                    }
                }
            }

            assertTrue(
                "Black outline halo detected in $hdr: farBg=$farBgLuma, minRing=$minRingLuma",
                minRingLuma >= farBgLuma - 8
            )
            result.bitmap.recycle()
        }
        frame.recycle()
    }

    @Test
    fun `contrast and vibrancy sliders persist into final saved bitmap`() = runTest {
        val engine = HdrPlusEngine()
        val frame = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 64) {
                for (x in 0 until 64) {
                    // Left half muted dark warm, right half muted bright cool
                    if (x < 32) {
                        setPixel(x, y, Color.rgb(90, 75, 70))
                    } else {
                        setPixel(x, y, Color.rgb(165, 175, 190))
                    }
                }
            }
        }

        val lowRes = engine.processBurst(
            frames = listOf(frame),
            mode = CameraMode.CAMERA,
            hdrMode = HdrMode.HDR_PLUS_ENHANCED,
            manual = ManualExposureSettings(contrastAdjust = -0.8f, vibrancyAdjust = -0.8f),
            config = HdrPipelineConfig(),
        )
        val highRes = engine.processBurst(
            frames = listOf(frame),
            mode = CameraMode.CAMERA,
            hdrMode = HdrMode.HDR_PLUS_ENHANCED,
            manual = ManualExposureSettings(contrastAdjust = 0.8f, vibrancyAdjust = 0.8f),
            config = HdrPipelineConfig(),
        )

        val lowLeft = lowRes.bitmap.getPixel(16, 32)
        val lowRight = lowRes.bitmap.getPixel(48, 32)
        val highLeft = highRes.bitmap.getPixel(16, 32)
        val highRight = highRes.bitmap.getPixel(48, 32)

        val lowContrastSpread = Color.green(lowRight) - Color.green(lowLeft)
        val highContrastSpread = Color.green(highRight) - Color.green(highLeft)
        assertTrue(
            "Expected higher contrast spread ($highContrastSpread > $lowContrastSpread)",
            highContrastSpread > lowContrastSpread + 20
        )

        val lowChroma = Color.blue(lowRight) - Color.red(lowRight)
        val highChroma = Color.blue(highRight) - Color.red(highRight)
        assertTrue(
            "Expected higher vibrancy chroma ($highChroma > $lowChroma)",
            highChroma > lowChroma + 15
        )

        frame.recycle()
        lowRes.bitmap.recycle()
        highRes.bitmap.recycle()
    }

    @Test
    fun `pinch zoom clamps to optical bounds and lens selection switches active lens`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.example.ui.viewmodel.CameraViewModel(app)

        vm.onPinchZoom(2.5f)
        assertTrue(vm.uiState.value.currentZoomRatio >= 2.4f)
        assertTrue(vm.uiState.value.isZoomRulerVisible)

        vm.onPinchZoom(100f)
        assertEquals(vm.uiState.value.telemetry.maxZoomRatio, vm.uiState.value.currentZoomRatio)

        vm.selectLens(
            com.example.camera.model.LensOption(
                id = "uw_2",
                label = ".5",
                zoomRatio = 1f,
                cameraId = "2",
                focalLengthMm35 = 13,
            )
        )
        assertEquals("uw_2", vm.uiState.value.activeLensId)
        assertEquals("2", vm.uiState.value.preferredCameraId)

        vm.selectLens(
            com.example.camera.model.LensOption(
                id = "main_1x",
                label = "1x",
                zoomRatio = 1f,
                cameraId = null,
                focalLengthMm35 = 24,
            )
        )
        assertEquals("main_1x", vm.uiState.value.activeLensId)
        assertEquals(null, vm.uiState.value.preferredCameraId)
    }

    @Test
    fun `hdr plus aligns sub-coarse shifted exposure-varied burst frames without edge blur`() = runTest {
        val engine = HdrPlusEngine()
        val w = 240
        val h = 180

        fun drawCrispScene(shiftX: Int, shiftY: Int, exposureScale: Float): Bitmap {
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val sx = x + shiftX
                        val sy = y + shiftY
                        val inBox = sx in 70..169 && sy in 45..134
                        val base = if (inBox) 220f else 48f
                        val v = (base * exposureScale).toInt().coerceIn(0, 255)
                        setPixel(x, y, Color.rgb(v, v, v))
                    }
                }
            }
        }

        val refFrame = drawCrispScene(shiftX = 0, shiftY = 0, exposureScale = 1.0f)
        val shiftedUnderexposed = drawCrispScene(shiftX = 4, shiftY = -3, exposureScale = 0.65f)

        val result = engine.processBurst(
            frames = listOf(refFrame, shiftedUnderexposed),
            mode = CameraMode.CAMERA,
            hdrMode = HdrMode.HDR_PLUS_ENHANCED,
            manual = ManualExposureSettings(),
            config = HdrPipelineConfig(subPixelAlignment = true, sabreSharpening = 0.3f),
        )

        // Check crispness across the vertical boundary x = 69..70 (must not smear over 4 pixels)
        val outside = Color.red(result.bitmap.getPixel(68, 90))
        val inside = Color.red(result.bitmap.getPixel(71, 90))
        val step = inside - outside
        assertTrue("Expected crisp boundary after 4px shift alignment, got jump=$step", step > 100)

        refFrame.recycle()
        shiftedUnderexposed.recycle()
        result.bitmap.recycle()
    }

    @Test
    fun `hdr plus rejects severely motion-blurred secondary frame to keep output sharp`() = runTest {
        val engine = HdrPlusEngine()
        val w = 160
        val h = 120

        val sharpFrame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val v = if ((x / 6 + y / 6) % 2 == 0) 215 else 35
                    setPixel(x, y, Color.rgb(v, v, v))
                }
            }
        }

        // Uniform gray simulates extreme motion blur destroying all edges
        val blurredFrame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    setPixel(x, y, Color.rgb(125, 125, 125))
                }
            }
        }

        val result = engine.processBurst(
            frames = listOf(blurredFrame, sharpFrame),
            mode = CameraMode.CAMERA,
            hdrMode = HdrMode.HDR_PLUS_ENHANCED,
            manual = ManualExposureSettings(),
            config = HdrPipelineConfig(),
        )

        // Engine should pick sharpFrame as reference and reject blurredFrame
        assertEquals(1, result.metrics.framesMerged)
        sharpFrame.recycle()
        blurredFrame.recycle()
        result.bitmap.recycle()
    }

    @Test
    fun `user tone presets save into looks and restore tone sliders cleanly`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.example.ui.viewmodel.CameraViewModel(app)

        vm.updateShadowBoost(0.35f)
        vm.updateHighlightBoost(-0.25f)
        vm.updateContrastAdjust(0.20f)
        vm.updateVibrancyAdjust(0.45f)

        vm.saveCurrentToneAsPreset("Sunset Warm")
        val saved = vm.uiState.value.tonePresets.firstOrNull { it.name == "Sunset Warm" }
        assertTrue(saved != null)
        assertEquals(saved!!.id, vm.uiState.value.activePresetId)

        // Reset to Default
        vm.applyTonePreset(null)
        assertEquals(0f, vm.uiState.value.manualSettings.shadowBoost, 0.001f)
        assertEquals(null, vm.uiState.value.activePresetId)

        // Re-apply saved Look preset
        vm.applyTonePreset(saved)
        assertEquals(0.35f, vm.uiState.value.manualSettings.shadowBoost, 0.001f)
        assertEquals(-0.25f, vm.uiState.value.manualSettings.highlightBoost, 0.001f)
        assertEquals(0.20f, vm.uiState.value.manualSettings.contrastAdjust, 0.001f)
        assertEquals(0.45f, vm.uiState.value.manualSettings.vibrancyAdjust, 0.001f)

        // Delete preset cleanly
        vm.deleteTonePreset(saved.id)
        assertTrue(vm.uiState.value.tonePresets.none { it.id == saved.id })
    }

    @Test
    fun `zsl flash off default and fast single frame fused path produce clean output`() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.example.ui.viewmodel.CameraViewModel(app)
        assertEquals(com.example.camera.model.FlashMode.OFF, vm.uiState.value.flashMode)

        val engine = HdrPlusEngine()
        val frame = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 96) {
                for (x in 0 until 128) {
                    setPixel(x, y, Color.rgb(120, 140, 160))
                }
            }
        }
        val result = engine.processBurst(
            frames = listOf(frame),
            mode = CameraMode.CAMERA,
            hdrMode = HdrMode.OFF,
            manual = ManualExposureSettings(),
            config = HdrPipelineConfig(sabreSharpening = 0f, cinematicEffectEnabled = false),
        )
        assertEquals(1, result.metrics.framesMerged)
        assertEquals(32, result.lumaHistogram.size)
        val center = result.bitmap.getPixel(64, 48)
        assertTrue(abs(Color.red(center) - 120) <= 2)
        assertTrue(abs(Color.green(center) - 140) <= 2)
        assertTrue(abs(Color.blue(center) - 160) <= 2)
        frame.recycle()
        result.bitmap.recycle()
    }

    @Test
    fun `sabre deconvolution sharpens edges cleanly and attaches ultra hdr gainmap on api 34 plus`() = runTest {
        val engine = HdrPlusEngine()
        val w = 128
        val h = 96
        // Checkerboard with highlight quadrant to exercise both fine sharpening & HDR gainmap
        val softEdgeFrame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val v = when {
                        x < 64 && y < 48 -> 240
                        (x / 4 + y / 4) % 2 == 0 -> 160
                        else -> 95
                    }
                    setPixel(x, y, Color.rgb(v, v, v))
                }
            }
        }

        val unsharpened = engine.processBurst(
            frames = listOf(softEdgeFrame),
            mode = CameraMode.CAMERA,
            hdrMode = HdrMode.HDR_PLUS_ENHANCED,
            manual = ManualExposureSettings(),
            config = HdrPipelineConfig(sabreSharpening = 0f, ultraHdrEnabled = false),
        )
        val sharpened = engine.processBurst(
            frames = listOf(softEdgeFrame),
            mode = CameraMode.CAMERA,
            hdrMode = HdrMode.HDR_PLUS_ENHANCED,
            manual = ManualExposureSettings(),
            config = HdrPipelineConfig(sabreSharpening = 0.85f, ultraHdrEnabled = true),
        )

        // Compare edge step contrast across a 4px transition in the lower-right textured area
        fun edgeEnergy(bmp: Bitmap): Int {
            var sum = 0
            for (y in 54..90) {
                for (x in 10..118) {
                    val a = Color.red(bmp.getPixel(x, y))
                    val b = Color.red(bmp.getPixel(x + 1, y))
                    sum += abs(a - b)
                }
            }
            return sum
        }

        assertTrue(
            "Expected 2-scale Sabre sharpening to increase fine edge acutance",
            edgeEnergy(sharpened.bitmap) > edgeEnergy(unsharpened.bitmap)
        )
        assertEquals(false, unsharpened.metrics.isUltraHdr)

        // Check Ultra HDR Gainmap behavior (on Robolectric SDK 36, Gainmap may be attached cleanly if supported by shadow)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertEquals(sharpened.bitmap.hasGainmap(), sharpened.metrics.isUltraHdr)
        }

        softEdgeFrame.recycle()
        unsharpened.bitmap.recycle()
        sharpened.bitmap.recycle()
    }

    @Test
    fun `cinematic effect is enabled by default and adds subtle darkness contrast and vibrancy`() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.example.ui.viewmodel.CameraViewModel(app)
        assertTrue(vm.uiState.value.hdrConfig.cinematicEffectEnabled)

        val engine = HdrPlusEngine()
        val frame = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 64) {
                for (x in 0 until 64) {
                    if (x < 32) {
                        setPixel(x, y, Color.rgb(95, 80, 75))
                    } else {
                        setPixel(x, y, Color.rgb(165, 175, 195))
                    }
                }
            }
        }

        val offRes = engine.processBurst(
            frames = listOf(frame),
            mode = CameraMode.CAMERA,
            hdrMode = HdrMode.HDR_PLUS,
            manual = ManualExposureSettings(),
            config = HdrPipelineConfig(cinematicEffectEnabled = false, sabreSharpening = 0f),
        )
        val onRes = engine.processBurst(
            frames = listOf(frame),
            mode = CameraMode.CAMERA,
            hdrMode = HdrMode.HDR_PLUS,
            manual = ManualExposureSettings(),
            config = HdrPipelineConfig(cinematicEffectEnabled = true, sabreSharpening = 0f),
        )

        val offLeft = offRes.bitmap.getPixel(16, 32)
        val offRight = offRes.bitmap.getPixel(48, 32)
        val onLeft = onRes.bitmap.getPixel(16, 32)
        val onRight = onRes.bitmap.getPixel(48, 32)

        val offMidLuma = (Color.green(offLeft) + Color.green(offRight)) / 2
        val onMidLuma = (Color.green(onLeft) + Color.green(onRight)) / 2
        assertTrue("Expected cinematic effect to be slightly darker (on=$onMidLuma vs off=$offMidLuma)", onMidLuma < offMidLuma)

        val offSpread = Color.green(offRight) - Color.green(offLeft)
        val onSpread = Color.green(onRight) - Color.green(onLeft)
        assertTrue("Expected cinematic effect to boost contrast (on=$onSpread vs off=$offSpread)", onSpread > offSpread)

        val offChroma = Color.blue(offRight) - Color.red(offRight)
        val onChroma = Color.blue(onRight) - Color.red(onRight)
        assertTrue("Expected cinematic effect to boost vibrancy (on=$onChroma vs off=$offChroma)", onChroma > offChroma)

        frame.recycle()
        offRes.bitmap.recycle()
        onRes.bitmap.recycle()
    }

    @Test
    fun `tone shader neutrality accounts for cinematic effect and manual controls`() {
        assertTrue(
            ToneShaderMath.isNeutral(
                manual = ManualExposureSettings(),
                hdrMode = HdrMode.OFF,
                config = HdrPipelineConfig(cinematicEffectEnabled = false),
                hardwareManualSupported = true,
            )
        )
        assertFalse(
            ToneShaderMath.isNeutral(
                manual = ManualExposureSettings(),
                hdrMode = HdrMode.OFF,
                config = HdrPipelineConfig(cinematicEffectEnabled = true),
                hardwareManualSupported = true,
            )
        )
    }

    @Test
    fun `reticle ev drag and unlock ae af update viewmodel state cleanly`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.example.ui.viewmodel.CameraViewModel(app)

        vm.onReticleEvDragStart()
        vm.onReticleEvChange(1.2f)
        assertEquals(1.2f, vm.uiState.value.manualSettings.exposureCompensationEv, 0.01f)
        vm.onReticleEvDragEnd()

        vm.unlockAeAf()
        assertEquals(null, vm.uiState.value.focusReticle)
    }

    @Test
    fun `low light detector evaluates hardware exposure and luma with hysteresis`() {
        val daylight = com.example.camera.model.LowLightDetector.computeSeverity(
            meanLuma = 0.58f,
            crushedShadowPercent = 2f,
            sensorIso = 100,
            exposureTimeNs = 2_000_000L, // 1/500s
        )
        assertTrue("Daylight should have low severity, got $daylight", daylight < 0.20f)
        assertFalse(com.example.camera.model.LowLightDetector.nextLowLightActiveState(false, daylight))

        // Dim indoor scene where AE already boosted ISO to 1600 and slowed shutter to 1/20s
        val dimRoom = com.example.camera.model.LowLightDetector.computeSeverity(
            meanLuma = 0.28f,
            crushedShadowPercent = 24f,
            sensorIso = 1600,
            exposureTimeNs = 50_000_000L,
        )
        assertTrue("Dim high-ISO room should trigger low light, got $dimRoom", dimRoom >= 0.48f)
        assertTrue(com.example.camera.model.LowLightDetector.nextLowLightActiveState(false, dimRoom))

        // Luma-only fallback (no Camera2 metadata)
        val pitchDarkFallback = com.example.camera.model.LowLightDetector.computeSeverity(
            meanLuma = 0.11f,
            crushedShadowPercent = 68f,
            sensorIso = null,
            exposureTimeNs = null,
        )
        assertTrue("Pitch dark frame must trigger low light, got $pitchDarkFallback", pitchDarkFallback >= 0.70f)

        // Hysteresis band (between 0.32 and 0.48) preserves current state without flickering
        assertTrue(com.example.camera.model.LowLightDetector.nextLowLightActiveState(true, 0.40f))
        assertFalse(com.example.camera.model.LowLightDetector.nextLowLightActiveState(false, 0.40f))
        assertFalse(com.example.camera.model.LowLightDetector.nextLowLightActiveState(true, 0.25f))
    }

    @Test
    fun `low light exposure planner holds shutter longer and respects hardware bounds`() {
        val plan = com.example.camera.model.LowLightDetector.planLongExposure(
            severity = 0.85f,
            meanLuma = 0.14f,
            currentIso = 2400,
            currentExposureNs = 33_333_333L, // 1/30s
            isoRange = 50..6400,
            exposureTimeRangeNs = 100_000L..1_000_000_000L,
        )
        assertTrue("Per-frame shutter should be held >= 250ms, got ${plan.perFrameExposureNs / 1_000_000L}ms", plan.perFrameExposureNs >= 250_000_000L)
        assertTrue("Per-frame shutter should stay handheld-safe <= 500ms", plan.perFrameExposureNs <= 500_000_000L)
        assertTrue("Target ISO should stay in hardware bounds", plan.targetIso in 50..6400)
        assertTrue("Night Sight should capture multiple frames (5..8), got ${plan.frameCount}", plan.frameCount in 5..8)
        assertTrue("Estimated hold should be >= 1.5s, got ${plan.estimatedTotalHoldMs}ms", plan.estimatedTotalHoldMs >= 1500L)
    }

    @Test
    fun `night sight engine brightens dark noisy burst and reduces color noise via streaming merge`() = runTest {
        val engine = HdrPlusEngine()
        val w = 96
        val h = 72
        val rng = java.util.Random(42L)

        // Simulate 6 dark noisy frames (mean ~32/255) with color speckles + one bright streetlamp spot
        val rawFrames = List(6) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val dx = x - 72
                    val dy = y - 20
                    if (dx * dx + dy * dy <= 6 * 6) {
                        bmp.setPixel(x, y, Color.rgb(245, 235, 210))
                    } else {
                        val nr = (32 + rng.nextInt(25) - 12).coerceIn(0, 255)
                        val ng = (32 + rng.nextInt(25) - 12).coerceIn(0, 255)
                        val nb = (32 + rng.nextInt(25) - 12).coerceIn(0, 255)
                        bmp.setPixel(x, y, Color.rgb(nr, ng, nb))
                    }
                }
            }
            com.example.camera.hardware.RawCapturedFrame.DecodedBitmap(bmp)
        }

        // Baseline single dark frame processed in normal HDR+ mode
        val normalRes = engine.processBurst(
            frames = listOf(rawFrames[0].bitmap),
            mode = CameraMode.CAMERA,
            hdrMode = HdrMode.HDR_PLUS_ENHANCED,
            manual = ManualExposureSettings(),
            config = HdrPipelineConfig(cinematicEffectEnabled = true),
            nightSightEnabled = false,
        )

        // Night Sight 6-frame streaming burst
        val nightRes = engine.processRawBurst(
            rawFrames = rawFrames,
            mode = CameraMode.CAMERA,
            hdrMode = HdrMode.HDR_PLUS_ENHANCED,
            manual = ManualExposureSettings(),
            config = HdrPipelineConfig(cinematicEffectEnabled = true),
            nightSightEnabled = true,
            lowLightSeverity = 0.85f,
            totalExposureSeconds = 2.4f,
        )

        assertTrue(nightRes.metrics.isNightSight)
        assertTrue("Expected Night Sight to merge multiple frames, got ${nightRes.metrics.framesMerged}", nightRes.metrics.framesMerged >= 4)

        fun avgShadowLumaAndChromaSpread(bmp: Bitmap): Pair<Double, Double> {
            var lumaSum = 0.0
            var chromaSpreadSum = 0.0
            var cnt = 0
            for (y in 20 until 64) {
                for (x in 8 until 50) {
                    val p = bmp.getPixel(x, y)
                    val r = Color.red(p)
                    val g = Color.green(p)
                    val b = Color.blue(p)
                    lumaSum += 0.299 * r + 0.587 * g + 0.114 * b
                    chromaSpreadSum += (maxOf(r, maxOf(g, b)) - minOf(r, minOf(g, b))).toDouble()
                    cnt++
                }
            }
            return (lumaSum / cnt) to (chromaSpreadSum / cnt)
        }

        val (normalLuma, normalChromaNoise) = avgShadowLumaAndChromaSpread(normalRes.bitmap)
        val (nightLuma, nightChromaNoise) = avgShadowLumaAndChromaSpread(nightRes.bitmap)

        assertTrue(
            "Expected Night Sight to brighten dark scene substantially (night=$nightLuma vs normal=$normalLuma)",
            nightLuma > normalLuma + 25.0
        )
        assertTrue(
            "Expected Night Sight temporal + chroma denoise to suppress color speckle noise (night=$nightChromaNoise vs normal=$normalChromaNoise)",
            nightChromaNoise < normalChromaNoise * 0.5
        )

        rawFrames.forEach { it.release() }
        normalRes.bitmap.recycle()
        nightRes.bitmap.recycle()
    }

    @Test
    fun `camera viewmodel toggles auto night sight on low light and respects flash and manual overrides`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.example.ui.viewmodel.CameraViewModel(app)

        assertEquals(com.example.camera.model.NightSightMode.AUTO, vm.uiState.value.nightSightMode)
        assertFalse(vm.uiState.value.isNightSightActive)

        // Feed low-light analysis
        vm.updateLowLightDetection(
            meanLuma = 0.12f,
            crushedShadowPercent = 62f,
            sensorIso = 2400,
            exposureTimeNs = 50_000_000L,
        )
        assertTrue(vm.uiState.value.isLowLightDetected)
        assertTrue(vm.uiState.value.isNightSightActive)
        assertTrue(vm.uiState.value.estimatedNightHoldSeconds >= 1.0f)

        // Turning flash ON suppresses Auto Night Sight
        vm.setFlashMode(com.example.camera.model.FlashMode.ON)
        assertFalse(vm.uiState.value.isNightSightActive)

        // Returning flash to OFF restores Auto Night Sight
        vm.setFlashMode(com.example.camera.model.FlashMode.OFF)
        assertTrue(vm.uiState.value.isNightSightActive)

        // Manual ISO override suppresses Auto Night Sight
        vm.updateIso(400)
        assertFalse(vm.uiState.value.isNightSightActive)
        vm.resetManualSettings()
        assertTrue(vm.uiState.value.isNightSightActive)

        // Quick toggle pauses Night Sight
        vm.toggleNightSightQuick()
        assertEquals(com.example.camera.model.NightSightMode.OFF, vm.uiState.value.nightSightMode)
        assertFalse(vm.uiState.value.isNightSightActive)

        // Bright light + NightSightMode.ON forces Night Sight active
        repeat(6) {
            vm.updateLowLightDetection(
                meanLuma = 0.70f,
                crushedShadowPercent = 1f,
                sensorIso = 100,
                exposureTimeNs = 1_000_000L,
            )
        }
        assertFalse(vm.uiState.value.isLowLightDetected)
        vm.setNightSightMode(com.example.camera.model.NightSightMode.ON)
        assertTrue(vm.uiState.value.isNightSightActive)
    }

    @Test
    fun `low light planner uses exposure equivalence and prevents overexposure across scenes`() {
        // 1) Well-exposed dim indoor scene (AE already hit luma 0.38 via noisy ISO 1600 @ 1/30s)
        // Night Sight should lengthen shutter and drop ISO proportionally without boosting brightness
        val dimIso = 1600
        val dimExpNs = 33_333_333L
        val dimPlan = com.example.camera.model.LowLightDetector.planLongExposure(
            severity = 0.65f,
            meanLuma = 0.38f,
            currentIso = dimIso,
            currentExposureNs = dimExpNs,
            isoRange = 50..6400,
            exposureTimeRangeNs = 100_000L..1_000_000_000L,
            clippedHighlightPercent = 0f,
        )
        val dimBaselineProduct = dimIso.toDouble() * dimExpNs.toDouble()
        val dimPlannedProduct = dimPlan.targetIso.toDouble() * dimPlan.perFrameExposureNs.toDouble()
        assertTrue("Expected shutter to lengthen for noise reduction, got ${dimPlan.perFrameExposureNs / 1_000_000L}ms", dimPlan.perFrameExposureNs >= 180_000_000L)
        assertTrue("Expected ISO to drop substantially from 1600, got ${dimPlan.targetIso}", dimPlan.targetIso <= 400)
        assertTrue(
            "Well-exposed dim scene should stay near 1.0x baseline exposure (ratio=${dimPlannedProduct / dimBaselineProduct})",
            dimPlannedProduct <= dimBaselineProduct * 1.08 && dimPlannedProduct >= dimBaselineProduct * 0.90
        )
        assertTrue("Expected minimal EV boost on well-exposed scene, got ${dimPlan.evBoostStops}", dimPlan.evBoostStops <= 0.15f)

        // 2) Bright scene where user manually forces Night Sight ON (ISO 100 @ 1/125s, luma 0.62)
        // Shutter MUST be capped when ISO hits floor (50) so it never overexposes!
        val brightIso = 100
        val brightExpNs = 8_000_000L
        val brightPlan = com.example.camera.model.LowLightDetector.planLongExposure(
            severity = 0.45f,
            meanLuma = 0.62f,
            currentIso = brightIso,
            currentExposureNs = brightExpNs,
            isoRange = 50..6400,
            exposureTimeRangeNs = 100_000L..1_000_000_000L,
            clippedHighlightPercent = 2f,
        )
        val brightBaselineProduct = brightIso.toDouble() * brightExpNs.toDouble()
        val brightPlannedProduct = brightPlan.targetIso.toDouble() * brightPlan.perFrameExposureNs.toDouble()
        assertTrue(
            "Forced Night Sight in bright light must cap shutter to avoid overexposure (ratio=${brightPlannedProduct / brightBaselineProduct})",
            brightPlannedProduct <= brightBaselineProduct * 1.05
        )
        assertTrue("Expected shutter capped <= 20ms on bright scene, got ${brightPlan.perFrameExposureNs / 1_000_000L}ms", brightPlan.perFrameExposureNs <= 20_000_000L)

        // 3) Dark scene with bright lamps/highlights should damp EV boost compared to dark scene without highlights
        val darkCleanPlan = com.example.camera.model.LowLightDetector.planLongExposure(
            severity = 0.85f,
            meanLuma = 0.14f,
            currentIso = 1600,
            currentExposureNs = 33_333_333L,
            isoRange = 50..6400,
            exposureTimeRangeNs = 100_000L..1_000_000_000L,
            clippedHighlightPercent = 0f,
        )
        val darkHighlightPlan = com.example.camera.model.LowLightDetector.planLongExposure(
            severity = 0.85f,
            meanLuma = 0.14f,
            currentIso = 1600,
            currentExposureNs = 33_333_333L,
            isoRange = 50..6400,
            exposureTimeRangeNs = 100_000L..1_000_000_000L,
            clippedHighlightPercent = 8f,
        )
        assertTrue(
            "Clipped highlights should damp Night Sight EV boost (clean=${darkCleanPlan.evBoostStops}, clipped=${darkHighlightPlan.evBoostStops})",
            darkHighlightPlan.evBoostStops < darkCleanPlan.evBoostStops
        )
    }

    @Test
    fun `night sight engine avoids double brightening well exposed frames and pulls back hot frames`() = runTest {
        val engine = HdrPlusEngine()
        val w = 64
        val h = 64

        fun meanLuma(bmp: Bitmap): Double {
            var sum = 0.0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val p = bmp.getPixel(x, y)
                    sum += 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
                }
            }
            return sum / (w * h)
        }

        // Case A: Already well-exposed long-shutter frame (mean ~ 108/255 ≈ 0.42)
        val wellExposed = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val v = if (x < 32) 96 else 120
                    setPixel(x, y, Color.rgb(v, v, v))
                }
            }
        }
        val wellExposedRes = engine.processBurst(
            frames = listOf(wellExposed),
            mode = CameraMode.CAMERA,
            hdrMode = HdrMode.HDR_PLUS_ENHANCED,
            manual = ManualExposureSettings(),
            config = HdrPipelineConfig(sabreSharpening = 0f),
            nightSightEnabled = true,
            lowLightSeverity = 0.8f,
            totalExposureSeconds = 1.6f,
        )
        val wellInLuma = meanLuma(wellExposed)
        val wellOutLuma = meanLuma(wellExposedRes.bitmap)
        assertTrue(
            "Well-exposed Night Sight capture should not double-brighten (in=$wellInLuma, out=$wellOutLuma)",
            wellOutLuma <= wellInLuma + 14.0
        )

        // Case B: Slightly overexposed hardware frame (mean ~ 195/255 ≈ 0.76)
        val hotFrame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val v = if (x < 32) 180 else 210
                    setPixel(x, y, Color.rgb(v, v, v))
                }
            }
        }
        val hotRes = engine.processBurst(
            frames = listOf(hotFrame),
            mode = CameraMode.CAMERA,
            hdrMode = HdrMode.HDR_PLUS_ENHANCED,
            manual = ManualExposureSettings(),
            config = HdrPipelineConfig(sabreSharpening = 0f),
            nightSightEnabled = true,
            lowLightSeverity = 0.8f,
            totalExposureSeconds = 1.6f,
        )
        val hotInLuma = meanLuma(hotFrame)
        val hotOutLuma = meanLuma(hotRes.bitmap)
        assertTrue(
            "Overexposed Night Sight capture should be pulled back, not brightened further (in=$hotInLuma, out=$hotOutLuma)",
            hotOutLuma < hotInLuma - 12.0
        )

        wellExposed.recycle()
        wellExposedRes.bitmap.recycle()
        hotFrame.recycle()
        hotRes.bitmap.recycle()
    }

    @Test
    fun `low light mode automatically disables all hdr modes and restores them when exiting low light`() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.example.ui.viewmodel.CameraViewModel(app)

        // Default daylight state: HDR+ Enhanced and Ultra HDR are active
        assertEquals(HdrMode.HDR_PLUS_ENHANCED, vm.uiState.value.hdrMode)
        assertEquals(HdrMode.HDR_PLUS_ENHANCED, vm.uiState.value.effectiveHdrMode)
        assertTrue(vm.uiState.value.effectiveUltraHdrEnabled)
        assertFalse(vm.uiState.value.isNightSightActive)

        // Enter Auto low light -> all HDR modes automatically disabled while saved preference is preserved
        vm.updateLowLightDetection(
            meanLuma = 0.12f,
            crushedShadowPercent = 65f,
            sensorIso = 2400,
            exposureTimeNs = 50_000_000L,
        )
        assertTrue(vm.uiState.value.isNightSightActive)
        assertEquals(HdrMode.OFF, vm.uiState.value.effectiveHdrMode)
        assertFalse(vm.uiState.value.effectiveUltraHdrEnabled)
        assertEquals(HdrMode.HDR_PLUS_ENHANCED, vm.uiState.value.hdrMode)

        // Attempting to cycle or enable HDR while in Low Light mode keeps HDR off and shows banner
        vm.cycleHdrMode()
        assertEquals(HdrMode.OFF, vm.uiState.value.effectiveHdrMode)
        assertEquals(HdrMode.HDR_PLUS_ENHANCED, vm.uiState.value.hdrMode)
        assertEquals("HDR disabled in Low Light mode", vm.uiState.value.statusBannerMessage)

        vm.setHdrMode(HdrMode.HDR_PLUS)
        assertEquals(HdrMode.OFF, vm.uiState.value.effectiveHdrMode)
        assertEquals(HdrMode.HDR_PLUS_ENHANCED, vm.uiState.value.hdrMode)

        // Turning Night Sight OFF immediately restores user's HDR+ Enhanced and Ultra HDR
        vm.setNightSightMode(com.example.camera.model.NightSightMode.OFF)
        assertFalse(vm.uiState.value.isNightSightActive)
        assertEquals(HdrMode.HDR_PLUS_ENHANCED, vm.uiState.value.effectiveHdrMode)
        assertTrue(vm.uiState.value.effectiveUltraHdrEnabled)

        // Forcing Night Sight ON even in bright light disables all HDR modes again
        repeat(6) {
            vm.updateLowLightDetection(
                meanLuma = 0.72f,
                crushedShadowPercent = 0f,
                sensorIso = 100,
                exposureTimeNs = 1_000_000L,
            )
        }
        assertFalse(vm.uiState.value.isLowLightDetected)
        vm.setNightSightMode(com.example.camera.model.NightSightMode.ON)
        assertTrue(vm.uiState.value.isNightSightActive)
        assertEquals(HdrMode.OFF, vm.uiState.value.effectiveHdrMode)
        assertFalse(vm.uiState.value.effectiveUltraHdrEnabled)

        // HdrPlusEngine itself also disables HDR highlight recovery & Ultra HDR gainmap when nightSightEnabled = true
        val engine = HdrPlusEngine()
        val frame = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 64) {
                for (x in 0 until 64) {
                    val v = if (x < 32) 40 else 235
                    setPixel(x, y, Color.rgb(v, v, v))
                }
            }
        }
        val nightResult = engine.processBurst(
            frames = listOf(frame),
            mode = CameraMode.CAMERA,
            hdrMode = HdrMode.HDR_PLUS_ENHANCED,
            manual = ManualExposureSettings(),
            config = HdrPipelineConfig(ultraHdrEnabled = true, highlightRecovery = 0.9f),
            nightSightEnabled = true,
            lowLightSeverity = 0.8f,
            totalExposureSeconds = 1.8f,
        )
        assertTrue(nightResult.metrics.isNightSight)
        assertEquals(false, nightResult.metrics.isUltraHdr)
        assertEquals(0f, nightResult.metrics.highlightRecoveryApplied, 0.0001f)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertFalse(nightResult.bitmap.hasGainmap())
        }
        frame.recycle()
        nightResult.bitmap.recycle()
    }

    @Test
    fun `backlit scene detector identifies high contrast backlighting without false positives`() {
        // Strong bright sky/window behind a dark unlit foreground subject
        assertTrue(
            com.example.camera.model.LowLightDetector.isBacklitScene(
                clippedHighlightPercent = 12f,
                crushedShadowPercent = 22f,
                meanLuma = 0.44f,
            )
        )
        // Normal evenly-lit daylight scene
        assertFalse(
            com.example.camera.model.LowLightDetector.isBacklitScene(
                clippedHighlightPercent = 1.5f,
                crushedShadowPercent = 3f,
                meanLuma = 0.55f,
            )
        )
        // Pitch dark night scene (no blown backlight)
        assertFalse(
            com.example.camera.model.LowLightDetector.isBacklitScene(
                clippedHighlightPercent = 0.5f,
                crushedShadowPercent = 72f,
                meanLuma = 0.12f,
            )
        )
    }

    @Test
    fun `hdr plus engine fuses backlit exposure brackets and skips redundant processing for pixel extension captures`() = runTest {
        val engine = HdrPlusEngine()
        val w = 96
        val h = 72

        // Simulate a backlit portrait: left half is dark unlit subject, right half is bright blown window
        fun makeBacklitFrame(subjectVal: Int, windowVal: Int, evBias: Float): com.example.camera.hardware.RawCapturedFrame.DecodedBitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val v = if (x < w / 2) subjectVal else windowVal
                    bmp.setPixel(x, y, Color.rgb(v, v, v))
                }
            }
            return com.example.camera.hardware.RawCapturedFrame.DecodedBitmap(bmp, exposureBiasEv = evBias)
        }

        val baseFrame = makeBacklitFrame(subjectVal = 28, windowVal = 248, evBias = 0f)
        val plusEvFrame = makeBacklitFrame(subjectVal = 115, windowVal = 255, evBias = 1.5f)
        val minusEvFrame = makeBacklitFrame(subjectVal = 14, windowVal = 175, evBias = -1.0f)

        val singleRes = engine.processRawBurst(
            rawFrames = listOf(baseFrame),
            mode = CameraMode.CAMERA,
            hdrMode = HdrMode.HDR_PLUS_ENHANCED,
            manual = ManualExposureSettings(),
            config = HdrPipelineConfig(sabreSharpening = 0f),
        )
        val bracketRes = engine.processRawBurst(
            rawFrames = listOf(baseFrame, plusEvFrame, minusEvFrame),
            mode = CameraMode.CAMERA,
            hdrMode = HdrMode.HDR_PLUS_ENHANCED,
            manual = ManualExposureSettings(),
            config = HdrPipelineConfig(sabreSharpening = 0f),
        )

        val singleSubjectLuma = Color.red(singleRes.bitmap.getPixel(w / 4, h / 2))
        val bracketSubjectLuma = Color.red(bracketRes.bitmap.getPixel(w / 4, h / 2))
        val singleWindowLuma = Color.red(singleRes.bitmap.getPixel(3 * w / 4, h / 2))
        val bracketWindowLuma = Color.red(bracketRes.bitmap.getPixel(3 * w / 4, h / 2))

        assertTrue(
            "Expected +1.5 EV bracket fusion to lift backlit subject shadows (bracket=$bracketSubjectLuma vs single=$singleSubjectLuma)",
            bracketSubjectLuma > singleSubjectLuma + 18
        )
        assertTrue(
            "Expected -1.0 EV bracket fusion to recover blown backlight highlights (bracket=$bracketWindowLuma vs single=$singleWindowLuma)",
            bracketWindowLuma < singleWindowLuma - 12
        )

        // When Pixel Camera Services handled the capture natively, HdrPlusEngine acts as a clean pass-through
        val extRes = engine.processRawBurst(
            rawFrames = listOf(baseFrame),
            mode = CameraMode.CAMERA,
            hdrMode = HdrMode.HDR_PLUS_ENHANCED,
            manual = ManualExposureSettings(),
            config = HdrPipelineConfig(ultraHdrEnabled = true, highlightRecovery = 0.9f, sabreSharpening = 0.8f),
            isPixelExtensionCapture = true,
        )
        assertEquals(1, extRes.metrics.framesMerged)
        assertEquals(0f, extRes.metrics.highlightRecoveryApplied, 0.0001f)
        assertFalse(extRes.metrics.isUltraHdr)

        baseFrame.release()
        plusEvFrame.release()
        minusEvFrame.release()
        singleRes.bitmap.recycle()
        bracketRes.bitmap.recycle()
        extRes.bitmap.recycle()
    }

    @Test
    fun `camera viewmodel pixel camera services handles night-only devices, auto low-light routing, and native ultra hdr`() {
        // 1. Night-only device (stock Pixel 10: only ExtensionMode.NIGHT is exposed)
        val pixel10DaylightAuto = com.example.ui.viewmodel.CameraUiState(
            pixelCameraServicesEnabled = true,
            pixelExtensionsSupported = true,
            supportedPixelExtensions = setOf(com.example.camera.model.PixelExtensionMode.NIGHT),
            hasCheckedPixelExtensions = true,
            activePixelExtensionMode = com.example.camera.model.PixelExtensionMode.NONE,
            nightSightMode = com.example.camera.model.NightSightMode.AUTO,
            isLowLightDetected = false,
            hdrMode = com.example.camera.model.HdrMode.HDR_PLUS_ENHANCED,
        )
        assertTrue(pixel10DaylightAuto.isPixelNightOnlyDevice)
        // Daylight does NOT force Pixel Night; stays on custom HDR+ + Native Ultra HDR with Pro unlocked!
        assertEquals(com.example.camera.model.PixelExtensionMode.NONE, pixel10DaylightAuto.desiredPixelExtensionMode)
        assertFalse(pixel10DaylightAuto.isPixelServicesActive)
        assertFalse(pixel10DaylightAuto.isPixelServicesLocked)
        assertTrue(pixel10DaylightAuto.effectiveUltraHdrEnabled)
        assertTrue(pixel10DaylightAuto.wantsNativeUltraHdrCapture)

        // When Auto Night Sight detects low light on Pixel 10, it automatically routes to Pixel Night!
        val pixel10LowLightAuto = pixel10DaylightAuto.copy(isLowLightDetected = true)
        assertTrue(pixel10LowLightAuto.isAutoNightSightTriggered)
        assertEquals(com.example.camera.model.PixelExtensionMode.NIGHT, pixel10LowLightAuto.desiredPixelExtensionMode)

        // Once Pixel Night is bound, incompatible controls & HDR are locked cleanly
        val pixel10NightBound = pixel10LowLightAuto.copy(
            activePixelExtensionMode = com.example.camera.model.PixelExtensionMode.NIGHT,
            isAnalysisActiveInSession = false,
        )
        assertTrue(pixel10NightBound.isPixelServicesActive)
        assertTrue(pixel10NightBound.isPixelServicesLocked)
        assertTrue(pixel10NightBound.isNightSightActive)
        assertEquals(com.example.camera.model.HdrMode.OFF, pixel10NightBound.effectiveHdrMode)
        assertFalse(pixel10NightBound.effectiveUltraHdrEnabled)
        assertFalse(pixel10NightBound.wantsNativeUltraHdrCapture)

        // Night Sight = ON on Pixel 10 requests Pixel Night immediately
        val pixel10NightOn = pixel10DaylightAuto.copy(nightSightMode = com.example.camera.model.NightSightMode.ON)
        assertEquals(com.example.camera.model.PixelExtensionMode.NIGHT, pixel10NightOn.desiredPixelExtensionMode)

        // Night Sight = OFF on Pixel 10 stays on custom engine + Native Ultra HDR (never fails into unavailable error)
        val pixel10NightOff = pixel10DaylightAuto.copy(nightSightMode = com.example.camera.model.NightSightMode.OFF)
        assertEquals(com.example.camera.model.PixelExtensionMode.NONE, pixel10NightOff.desiredPixelExtensionMode)
        assertTrue(pixel10NightOff.wantsNativeUltraHdrCapture)

        // 2. Multi-extension device (supports AUTO, HDR, and NIGHT)
        val multiExtState = com.example.ui.viewmodel.CameraUiState(
            pixelCameraServicesEnabled = true,
            pixelExtensionsSupported = true,
            supportedPixelExtensions = setOf(
                com.example.camera.model.PixelExtensionMode.AUTO,
                com.example.camera.model.PixelExtensionMode.HDR,
                com.example.camera.model.PixelExtensionMode.NIGHT,
            ),
            hasCheckedPixelExtensions = true,
            nightSightMode = com.example.camera.model.NightSightMode.AUTO,
            hdrMode = com.example.camera.model.HdrMode.HDR_PLUS_ENHANCED,
        )
        assertFalse(multiExtState.isPixelNightOnlyDevice)
        assertEquals(com.example.camera.model.PixelExtensionMode.AUTO, multiExtState.desiredPixelExtensionMode)
        assertEquals(
            com.example.camera.model.PixelExtensionMode.HDR,
            multiExtState.copy(nightSightMode = com.example.camera.model.NightSightMode.OFF).desiredPixelExtensionMode,
        )
        assertEquals(
            com.example.camera.model.PixelExtensionMode.NIGHT,
            multiExtState.copy(nightSightMode = com.example.camera.model.NightSightMode.ON).desiredPixelExtensionMode,
        )

        // 3. ViewModel interaction: enabling Pixel Camera Services in Night Sight ON mode closes & locks Pro controls
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = com.example.ui.viewmodel.CameraViewModel(app)
        assertFalse(vm.uiState.value.pixelCameraServicesEnabled)
        assertFalse(vm.uiState.value.isPixelServicesLocked)

        vm.setNightSightMode(com.example.camera.model.NightSightMode.ON)
        vm.toggleProPanel()
        vm.updateIso(800)
        vm.updateShutterSpeedNs(10_000_000L)
        vm.updateWhiteBalanceKelvin(4200)
        vm.updateFocusDistance(2.5f)
        assertTrue(vm.uiState.value.isProPanelExpanded)
        assertEquals(800, vm.uiState.value.manualSettings.iso)

        vm.setPixelCameraServicesEnabled(true)
        val enabledState = vm.uiState.value
        assertTrue(enabledState.pixelCameraServicesEnabled)
        assertFalse(enabledState.isProPanelExpanded)
        assertFalse(enabledState.isAnalysisActiveInSession)
        assertEquals(null, enabledState.manualSettings.iso)
        assertEquals(null, enabledState.manualSettings.shutterSpeedNs)
        assertEquals(null, enabledState.manualSettings.whiteBalanceKelvin)
        assertEquals(null, enabledState.manualSettings.focusDistanceDiopter)
        assertEquals(com.example.camera.model.PixelExtensionMode.NIGHT, enabledState.desiredPixelExtensionMode)

        // Disable Pixel Camera Services: restores custom engine and unlocks Pro controls
        vm.setPixelCameraServicesEnabled(false)
        assertFalse(vm.uiState.value.pixelCameraServicesEnabled)
        assertFalse(vm.uiState.value.isPixelServicesLocked)
        assertTrue(vm.uiState.value.isAnalysisActiveInSession)
        assertEquals(com.example.camera.model.PixelExtensionMode.NONE, vm.uiState.value.desiredPixelExtensionMode)

        vm.toggleProPanel()
        assertTrue(vm.uiState.value.isProPanelExpanded)
        vm.updateIso(400)
        assertEquals(400, vm.uiState.value.manualSettings.iso)
    }
}
