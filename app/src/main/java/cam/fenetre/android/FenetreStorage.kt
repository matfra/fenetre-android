package cam.fenetre.android

import android.content.Context
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class FenetreStorage(context: Context, private val settings: FenetreCameraSettings = FenetreCameraSettings(context)) {
    private val rootDir = File(context.getExternalFilesDir(null), "fenetre")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val fileFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ssz'.jpg'", Locale.US)

    private fun cameraName(): String = settings.cameraName()

    fun nextPhotoFile(now: ZonedDateTime = ZonedDateTime.now()): File {
        val dayDir = File(File(File(rootDir, "photos"), cameraName()), now.format(dateFormatter))
        dayDir.mkdirs()
        return File(dayDir, now.format(fileFormatter))
    }

    fun latestFile(): File {
        val cameraDir = File(File(rootDir, "photos"), cameraName())
        cameraDir.mkdirs()
        return File(cameraDir, "latest.jpg")
    }

    fun metadataFile(): File {
        val cameraDir = File(File(rootDir, "photos"), cameraName())
        cameraDir.mkdirs()
        return File(cameraDir, "metadata.json")
    }

    fun currentDayDir(now: ZonedDateTime = ZonedDateTime.now()): File {
        val dayDir = File(File(File(rootDir, "photos"), cameraName()), now.format(dateFormatter))
        dayDir.mkdirs()
        return dayDir
    }

    fun dayDirs(): List<File> {
        val cameraDir = File(File(rootDir, "photos"), cameraName())
        return cameraDir.listFiles { file -> file.isDirectory }
            ?.filter { DAY_DIR_PATTERN.matches(it.name) }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    fun cameraDir(): File {
        val cameraDir = File(File(rootDir, "photos"), cameraName())
        cameraDir.mkdirs()
        return cameraDir
    }

    fun writeTimelapseMetadata(dayDir: File, playlistName: String, segmentCount: Int) {
        File(dayDir, "timelapse.json").writeText(
            """
            {
              "playlist": "$playlistName",
              "segment_count": $segmentCount,
              "updated_at_ms": ${System.currentTimeMillis()}
            }
            """.trimIndent() + "\n"
        )
    }

    fun writeDailyTimelapseMetadata(dayDir: File, videoName: String, imageCount: Int) {
        File(dayDir, "daily-timelapse.json").writeText(
            """
            {
              "video": "$videoName",
              "image_count": $imageCount,
              "updated_at_ms": ${System.currentTimeMillis()}
            }
            """.trimIndent() + "\n"
        )
    }

    fun writeMetadata(
        photoFile: File,
        lensMode: LensMode,
        rotationDegrees: Int,
        exposureMode: ExposureMode,
        captureMode: ExposureMode,
        manualExposureSettings: ManualExposureSettings?,
        exposureComposite: Double?,
        imageBrightness: Double?,
        captureExif: CaptureExif,
    ) {
        val cameraName = cameraName()
        val cameraDir = File(File(rootDir, "photos"), cameraName)
        val relativePath = photoFile.relativeTo(cameraDir).path
        metadataFile().writeText(
            """
            {
              "last_picture_url": "$relativePath",
              "camera_name": "$cameraName",
              "source": "android_camerax",
              "lens_mode": "${lensMode.name.lowercase()}",
              "rotation_degrees": $rotationDegrees,
              "exposure_mode": "${exposureMode.name.lowercase()}",
              "capture_mode": "${captureMode.name.lowercase()}",
              "requested_exposure_time": ${manualExposureSettings?.exposureTimeSeconds()?.toString() ?: "null"},
              "requested_iso": ${manualExposureSettings?.iso?.toString() ?: "null"},
              "exposure_composite": ${exposureComposite?.toString() ?: "null"},
              "image_brightness": ${imageBrightness?.toString() ?: "null"},
              "iso": ${captureExif.iso?.toString() ?: "null"},
              "exposure_time": ${captureExif.exposureTimeSeconds?.toString() ?: "null"},
              "shutter_speed": ${captureExif.shutterSpeed?.let { "\"$it\"" } ?: "null"},
              "white_balance": ${captureExif.whiteBalance?.let { "\"$it\"" } ?: "null"},
              "timestamp_overlay": ${settings.timestampOverlayEnabled()},
              "sun_path_overlay": ${settings.sunPathOverlayEnabled()},
              "overlay_timezone": "${settings.overlayTimezone()}",
              "overlay_lat": ${settings.overlayLatitude()},
              "overlay_lon": ${settings.overlayLongitude()},
              "captured_at_ms": ${System.currentTimeMillis()}
            }
            """.trimIndent() + "\n"
        )
    }

    fun rootPath(): String = rootDir.absolutePath

    fun rootDir(): File = rootDir

    companion object {
        private val DAY_DIR_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
