package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.camera.model.AspectRatioMode
import com.example.camera.model.BacklightMode
import com.example.camera.model.GridOverlayType
import com.example.camera.model.HdrMode
import com.example.camera.model.HdrPipelineConfig
import com.example.camera.model.NightSightMode
import com.example.camera.model.TonePreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.cameraDataStore: DataStore<Preferences> by preferencesDataStore(name = "pixel_cam_settings")

data class PersistedCameraPreferences(
    val hdrMode: HdrMode = HdrMode.HDR_PLUS_ENHANCED,
    val nightSightMode: NightSightMode = NightSightMode.AUTO,
    val pixelCameraServicesEnabled: Boolean = false,
    val tonePresets: List<TonePreset> = emptyList(),
    val activePresetId: String? = null,
    val gridOverlay: GridOverlayType = GridOverlayType.RULE_OF_THIRDS,
    val aspectRatio: AspectRatioMode = AspectRatioMode.RATIO_4_3,
    val showHistogram: Boolean = true,
    val showHorizonLevel: Boolean = true,
    val showFocusPeaking: Boolean = false,
    val showZebraHighlights: Boolean = false,
    val hdrConfig: HdrPipelineConfig = HdrPipelineConfig(),
)

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val HDR_MODE = stringPreferencesKey("hdr_mode")
        val NIGHT_SIGHT_MODE = stringPreferencesKey("night_sight_mode")
        val PIXEL_CAMERA_SERVICES = booleanPreferencesKey("pixel_camera_services")
        val TONE_PRESETS = stringPreferencesKey("tone_presets_json")
        val ACTIVE_PRESET_ID = stringPreferencesKey("active_preset_id")
        val GRID_OVERLAY = stringPreferencesKey("grid_overlay")
        val ASPECT_RATIO = stringPreferencesKey("aspect_ratio")
        val SHOW_HISTOGRAM = booleanPreferencesKey("show_histogram")
        val SHOW_HORIZON = booleanPreferencesKey("show_horizon")
        val SHOW_FOCUS_PEAKING = booleanPreferencesKey("show_focus_peaking")
        val SHOW_ZEBRAS = booleanPreferencesKey("show_zebras")
        val BURST_FRAMES = intPreferencesKey("burst_frames")
        val SUBPIXEL_ALIGN = booleanPreferencesKey("subpixel_align")
        val ULTRA_HDR = booleanPreferencesKey("ultra_hdr")
        val CINEMATIC_EFFECT = booleanPreferencesKey("cinematic_effect")
        val HIGHLIGHT_RECOVERY = floatPreferencesKey("highlight_recovery")
        val LOCAL_TONE_MAPPING = floatPreferencesKey("local_tone_mapping")
        val TEMPORAL_DENOISE = floatPreferencesKey("temporal_denoise")
        val SABRE_SHARPENING = floatPreferencesKey("sabre_sharpening")
        val RAW_DNG = booleanPreferencesKey("raw_dng")
        val MIRROR_FRONT = booleanPreferencesKey("mirror_front")
        val BACKLIGHT_MODE = stringPreferencesKey("backlight_mode")
        val BACKLIGHT_DEHAZE = floatPreferencesKey("backlight_dehaze")
        val BACKLIGHT_CLARITY = floatPreferencesKey("backlight_clarity")
    }

    val preferencesFlow: Flow<PersistedCameraPreferences> = context.cameraDataStore.data.map { prefs ->
        readPreferences(prefs)
    }

    suspend fun update(transform: (PersistedCameraPreferences) -> PersistedCameraPreferences) {
        context.cameraDataStore.edit { prefs ->
            val current = readPreferences(prefs)
            val updated = transform(current)
            prefs[Keys.HDR_MODE] = updated.hdrMode.name
            prefs[Keys.NIGHT_SIGHT_MODE] = updated.nightSightMode.name
            prefs[Keys.PIXEL_CAMERA_SERVICES] = updated.pixelCameraServicesEnabled
            prefs[Keys.TONE_PRESETS] = serializePresets(updated.tonePresets)
            if (updated.activePresetId != null) {
                prefs[Keys.ACTIVE_PRESET_ID] = updated.activePresetId
            } else {
                prefs.remove(Keys.ACTIVE_PRESET_ID)
            }
            prefs[Keys.GRID_OVERLAY] = updated.gridOverlay.name
            prefs[Keys.ASPECT_RATIO] = updated.aspectRatio.name
            prefs[Keys.SHOW_HISTOGRAM] = updated.showHistogram
            prefs[Keys.SHOW_HORIZON] = updated.showHorizonLevel
            prefs[Keys.SHOW_FOCUS_PEAKING] = updated.showFocusPeaking
            prefs[Keys.SHOW_ZEBRAS] = updated.showZebraHighlights
            prefs[Keys.BURST_FRAMES] = updated.hdrConfig.burstFrameCount
            prefs[Keys.SUBPIXEL_ALIGN] = updated.hdrConfig.subPixelAlignment
            prefs[Keys.ULTRA_HDR] = updated.hdrConfig.ultraHdrEnabled
            prefs[Keys.CINEMATIC_EFFECT] = updated.hdrConfig.cinematicEffectEnabled
            prefs[Keys.HIGHLIGHT_RECOVERY] = updated.hdrConfig.highlightRecovery
            prefs[Keys.LOCAL_TONE_MAPPING] = updated.hdrConfig.localToneMapping
            prefs[Keys.TEMPORAL_DENOISE] = updated.hdrConfig.temporalDenoise
            prefs[Keys.SABRE_SHARPENING] = updated.hdrConfig.sabreSharpening
            prefs[Keys.RAW_DNG] = updated.hdrConfig.rawDngEnabled
            prefs[Keys.MIRROR_FRONT] = updated.hdrConfig.mirrorFrontCamera
            prefs[Keys.BACKLIGHT_MODE] = updated.hdrConfig.backlightMode.name
            prefs[Keys.BACKLIGHT_DEHAZE] = updated.hdrConfig.backlightDehazeStrength
            prefs[Keys.BACKLIGHT_CLARITY] = updated.hdrConfig.backlightShadowClarity
        }
    }

    private fun readPreferences(prefs: Preferences): PersistedCameraPreferences {
        return PersistedCameraPreferences(
            hdrMode = prefs[Keys.HDR_MODE]?.let { runCatching { HdrMode.valueOf(it) }.getOrNull() }
                ?: HdrMode.HDR_PLUS_ENHANCED,
            nightSightMode = prefs[Keys.NIGHT_SIGHT_MODE]?.let { runCatching { NightSightMode.valueOf(it) }.getOrNull() }
                ?: NightSightMode.AUTO,
            pixelCameraServicesEnabled = prefs[Keys.PIXEL_CAMERA_SERVICES] ?: false,
            tonePresets = deserializePresets(prefs[Keys.TONE_PRESETS]),
            activePresetId = prefs[Keys.ACTIVE_PRESET_ID],
            gridOverlay = prefs[Keys.GRID_OVERLAY]?.let { runCatching { GridOverlayType.valueOf(it) }.getOrNull() }
                ?: GridOverlayType.RULE_OF_THIRDS,
            aspectRatio = prefs[Keys.ASPECT_RATIO]?.let { runCatching { AspectRatioMode.valueOf(it) }.getOrNull() }
                ?: AspectRatioMode.RATIO_4_3,
            showHistogram = prefs[Keys.SHOW_HISTOGRAM] ?: true,
            showHorizonLevel = prefs[Keys.SHOW_HORIZON] ?: true,
            showFocusPeaking = prefs[Keys.SHOW_FOCUS_PEAKING] ?: false,
            showZebraHighlights = prefs[Keys.SHOW_ZEBRAS] ?: false,
            hdrConfig = HdrPipelineConfig(
                burstFrameCount = prefs[Keys.BURST_FRAMES] ?: 3,
                subPixelAlignment = prefs[Keys.SUBPIXEL_ALIGN] ?: true,
                ultraHdrEnabled = prefs[Keys.ULTRA_HDR] ?: true,
                cinematicEffectEnabled = prefs[Keys.CINEMATIC_EFFECT] ?: true,
                highlightRecovery = prefs[Keys.HIGHLIGHT_RECOVERY] ?: 0.78f,
                localToneMapping = prefs[Keys.LOCAL_TONE_MAPPING] ?: 0.65f,
                temporalDenoise = prefs[Keys.TEMPORAL_DENOISE] ?: 0.70f,
                sabreSharpening = prefs[Keys.SABRE_SHARPENING] ?: 0.72f,
                rawDngEnabled = prefs[Keys.RAW_DNG] ?: false,
                mirrorFrontCamera = prefs[Keys.MIRROR_FRONT] ?: true,
                backlightMode = prefs[Keys.BACKLIGHT_MODE]?.let { runCatching { BacklightMode.valueOf(it) }.getOrNull() }
                    ?: BacklightMode.AUTO,
                backlightDehazeStrength = prefs[Keys.BACKLIGHT_DEHAZE] ?: 0.65f,
                backlightShadowClarity = prefs[Keys.BACKLIGHT_CLARITY] ?: 0.55f,
            )
        )
    }

    private fun serializePresets(presets: List<TonePreset>): String {
        val arr = JSONArray()
        presets.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("shadowBoost", p.shadowBoost.toDouble())
                    put("highlightBoost", p.highlightBoost.toDouble())
                    put("contrastAdjust", p.contrastAdjust.toDouble())
                    put("vibrancyAdjust", p.vibrancyAdjust.toDouble())
                    put("createdAtMs", p.createdAtMs)
                }
            )
        }
        return arr.toString()
    }

    private fun deserializePresets(raw: String?): List<TonePreset> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        TonePreset(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            shadowBoost = obj.optDouble("shadowBoost", 0.0).toFloat(),
                            highlightBoost = obj.optDouble("highlightBoost", 0.0).toFloat(),
                            contrastAdjust = obj.optDouble("contrastAdjust", 0.0).toFloat(),
                            vibrancyAdjust = obj.optDouble("vibrancyAdjust", 0.0).toFloat(),
                            createdAtMs = obj.optLong("createdAtMs", 0L),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
