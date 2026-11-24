package com.example.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import kotlin.math.floor

/**
 * Tactile camera haptic helper backed by system View haptic constants
 * (requires no VIBRATE permission and honors system touch-feedback settings).
 */
class CameraHaptics(private val view: View) {

    fun shutterClick() {
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        view.performHapticFeedback(code)
    }

    fun aeAfLock() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun actionClick() {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    fun sliderDetent(previousValue: Float, newValue: Float, stepSize: Float) {
        if (stepSize <= 0f) return
        val prevBucket = floor(previousValue / stepSize).toInt()
        val newBucket = floor(newValue / stepSize).toInt()
        if (prevBucket != newBucket) {
            tick()
        }
    }
}

@Composable
fun rememberCameraHaptics(): CameraHaptics {
    val view = LocalView.current
    return remember(view) { CameraHaptics(view) }
}
