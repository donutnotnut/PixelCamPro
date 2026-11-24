package com.example.camera.hardware

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

@Immutable
data class HorizonOrientation(
    val rollDegrees: Float = 0f,
    val pitchDegrees: Float = 0f,
    val isLevelAligned: Boolean = true,
)

class HorizonSensorMonitor(context: Context) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    fun observeOrientation(): Flow<HorizonOrientation> = callbackFlow {
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (sensorManager == null || sensor == null) {
            trySend(HorizonOrientation())
            awaitClose { }
            return@callbackFlow
        }

        var smoothRoll = 0f
        var smoothPitch = 0f
        var lastEmittedRoll = Float.NaN
        var lastEmittedAligned = true
        var lastEmitTimeMs = 0L
        val alpha = 0.22f

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val now = System.currentTimeMillis()
                if (now - lastEmitTimeMs < 33L) return

                val gx = event.values[0]
                val gy = event.values[1]
                val gz = event.values[2]

                val norm = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(0.001f)
                val nx = gx / norm
                val ny = gy / norm
                val nz = gz / norm

                // Roll around optical Z axis (0 deg when held upright in portrait)
                val rawRoll = Math.toDegrees(atan2(-nx.toDouble(), ny.toDouble())).toFloat()
                val rawPitch = Math.toDegrees(atan2(nz.toDouble(), sqrt((nx * nx + ny * ny).toDouble()))).toFloat()

                smoothRoll += alpha * (rawRoll - smoothRoll)
                smoothPitch += alpha * (rawPitch - smoothPitch)

                val isAligned = abs(smoothRoll) <= 1.2f
                val rollChanged = lastEmittedRoll.isNaN() || abs(smoothRoll - lastEmittedRoll) >= 0.25f
                if (!rollChanged && isAligned == lastEmittedAligned) return

                lastEmitTimeMs = now
                lastEmittedRoll = smoothRoll
                lastEmittedAligned = isAligned
                trySend(
                    HorizonOrientation(
                        rollDegrees = smoothRoll,
                        pitchDegrees = smoothPitch,
                        isLevelAligned = isAligned,
                    )
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }

    /**
     * Streams ambient light readings (in lux) from the device's light sensor.
     * Used only while Auto Pixel Night Sight is active (when ImageAnalysis is disabled) so the
     * camera can detect a return to bright daylight and seamlessly switch back to custom HDR+.
     */
    fun observeAmbientLux(): Flow<Float> = callbackFlow {
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (sensorManager == null || sensor == null) {
            awaitClose { }
            return@callbackFlow
        }

        var lastEmitMs = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val now = System.currentTimeMillis()
                if (now - lastEmitMs < 250L) return
                lastEmitMs = now
                val lux = event.values.firstOrNull() ?: return
                if (lux >= 0f && !lux.isNaN()) {
                    trySend(lux)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }
}
