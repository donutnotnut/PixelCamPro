package com.example.camera.processing

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.camera.model.CameraMode
import com.example.camera.model.CapturedPhotoRecord
import com.example.camera.model.HdrMode
import com.example.camera.model.HdrProcessMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import java.util.concurrent.atomic.AtomicInteger

class MediaStoreRepository(private val context: Context) {

    private val fileSequence = AtomicInteger(0)

    suspend fun saveProcessedPhoto(
        bitmap: Bitmap,
        mode: CameraMode,
        hdrMode: HdrMode,
        lookName: String,
        metrics: HdrProcessMetrics,
        isoUsed: Int,
        shutterUsedLabel: String,
        evUsed: Float,
        wbKelvinUsed: Int,
        hasDngRaw: Boolean,
        lumaHistogram: List<Int>,
    ): CapturedPhotoRecord? = withContext(Dispatchers.IO) {
        if (bitmap.isRecycled) return@withContext null
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now))
        val seq = fileSequence.incrementAndGet() % 100
        val fileName = String.format(Locale.US, "PXL_%s_%02d.jpg", stamp, seq)
        val isUltraHdr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && bitmap.hasGainmap()

        val tempFile = runCatching { File.createTempFile("pxl_out_", ".jpg", context.cacheDir) }.getOrNull()
        val resolver = context.contentResolver
        var uri: Uri? = null

        try {
            if (tempFile != null) {
                BufferedOutputStream(FileOutputStream(tempFile), 65_536).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                // Preserve intact Ultra HDR MPF/GContainer gainmap trailer on API 34+ without EXIF rewrite stripping it
                if (!isUltraHdr) {
                    runCatching {
                        val exif = ExifInterface(tempFile.absolutePath)
                        exif.setAttribute(ExifInterface.TAG_MAKE, "Google Pixel")
                        exif.setAttribute(ExifInterface.TAG_MODEL, "Pixel 11 Pro (PixelCam Engine)")
                        val modeTag = if (metrics.isNightSight) "Night Sight" else hdrMode.badge
                        exif.setAttribute(
                            ExifInterface.TAG_SOFTWARE,
                            "PixelCam Pro | $modeTag | $lookName (${metrics.framesMerged}f)"
                        )
                        exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, isoUsed.toString())
                        exif.setAttribute(
                            ExifInterface.TAG_USER_COMMENT,
                            "DR Gain +${String.format(Locale.US, "%.1f", metrics.dynamicRangeGainStops)} EV | Align ${(metrics.alignmentConfidence * 100).toInt()}% | WB ${wbKelvinUsed}K"
                        )
                        exif.saveAttributes()
                    }
                }
            }

            // Save directly into the default Pixel Camera folder: DCIM/Camera
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_TAKEN, now)
                put(MediaStore.Images.Media.WIDTH, bitmap.width)
                put(MediaStore.Images.Media.HEIGHT, bitmap.height)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null

            resolver.openOutputStream(uri)?.use { out ->
                val buffered = BufferedOutputStream(out, 65_536)
                if (tempFile != null && tempFile.exists()) {
                    tempFile.inputStream().use { input ->
                        input.copyTo(buffered, bufferSize = 65_536)
                    }
                    buffered.flush()
                } else {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, buffered)
                    buffered.flush()
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val doneValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
            }

            CapturedPhotoRecord(
                uriString = uri.toString(),
                displayName = fileName,
                timestampMs = now,
                width = bitmap.width,
                height = bitmap.height,
                mode = mode,
                hdrMode = hdrMode,
                lookName = lookName,
                metrics = metrics,
                isoUsed = isoUsed,
                shutterUsedLabel = shutterUsedLabel,
                evUsed = evUsed,
                wbKelvinUsed = wbKelvinUsed,
                hasDngRaw = hasDngRaw,
                isUltraHdr = isUltraHdr,
                isNightSight = metrics.isNightSight,
                lumaHistogram = lumaHistogram,
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            throw ce
        } catch (_: Throwable) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            null
        } finally {
            tempFile?.delete()
        }
    }

    suspend fun createDngOutputOptions(): Pair<Uri, ContentValues>? = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "PXL_${stamp}_RAW.dng"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext null
        uri to values
    }

    suspend fun deletePhoto(uriString: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(uriString)
            context.contentResolver.delete(uri, null, null) > 0
        }.getOrDefault(false)
    }

    suspend fun queryLatestMediaUri(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString()
                } else null
            }
        }.getOrNull()
    }
}
