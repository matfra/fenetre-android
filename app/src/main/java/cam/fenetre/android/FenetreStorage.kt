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

    fun latestSourceFile(): File {
        val cameraDir = File(File(rootDir, "photos"), cameraName())
        cameraDir.mkdirs()
        return File(cameraDir, "latest-source.jpg")
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

    fun writeDailyTimelapseMetadata(dayDir: File, videoName: String, imageCount: Int, encoderMode: DailyTimelapseEncoderMode) {
        File(dayDir, "daily-timelapse.json").writeText(
            """
            {
              "video": "$videoName",
              "encoder": "${encoderMode.name.lowercase()}",
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
        nightCaptureStrategy: NightCaptureStrategy,
        activeNightCaptureStrategy: NightCaptureStrategy,
        focusInfinityEnabled: Boolean,
        manualExposureSettings: ManualExposureSettings?,
        exposureComposite: Double?,
        imageBrightness: Double?,
        vignetteCorrectionApplied: Boolean,
        outputPostProcessApplied: Boolean,
        ssimResult: SsimResult,
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
              "night_capture_strategy": "${nightCaptureStrategy.name.lowercase()}",
              "night_capture_strategy_active": "${activeNightCaptureStrategy.name.lowercase()}",
              "focus_infinity_enabled": $focusInfinityEnabled,
              "capture_jpeg_size": ${jsonString(settings.captureJpegSize())},
              "requested_focus_distance_diopters": ${if (focusInfinityEnabled) "0.0" else "null"},
              "requested_exposure_time": ${manualExposureSettings?.exposureTimeSeconds()?.toString() ?: "null"},
              "requested_iso": ${manualExposureSettings?.iso?.toString() ?: "null"},
              "exposure_composite": ${exposureComposite?.toString() ?: "null"},
              "manual_night_target_luma": ${settings.manualNightTargetLuma()},
              "manual_to_auto_max_exposure_seconds": ${settings.manualToAutoMaxExposureSeconds()},
              "night_adaptive_iso_threshold": ${settings.nightAdaptiveIsoThreshold()},
              "vignette_correction_enabled": ${settings.vignetteCorrectionEnabled()},
              "vignette_correction_applied": $vignetteCorrectionApplied,
              "vignette_correction_strength": ${settings.vignetteCorrectionStrength()},
              "vignette_correction_power": ${settings.vignetteCorrectionPower()},
              "vignette_correction_radius": ${settings.vignetteCorrectionRadius()},
              "output_resize_size": ${jsonString(settings.outputResizeSize())},
              "output_crop_mode": ${jsonString(settings.outputCropMode().name.lowercase())},
              "output_crop_rect": ${jsonString(settings.outputCropRect())},
              "output_resize_applied": $outputPostProcessApplied,
              "output_postprocess_applied": $outputPostProcessApplied,
              "ssim_enabled": ${settings.ssimEnabled()},
              "ssim_value": ${ssimResult.value?.toString() ?: "null"},
              "ssim_target": ${ssimResult.target},
              "ssim_interval_seconds": ${ssimResult.intervalSeconds},
              "sky_area": ${jsonString(settings.skyArea())},
              "ssim_compared": ${ssimResult.compared},
              "star_detection_enabled": ${settings.starDetectionEnabled()},
              "star_capture_interval_seconds": ${settings.starCaptureIntervalSeconds()},
              "star_detection_min_count": ${settings.starDetectionMinCount()},
              "star_detection_threshold_luma": ${settings.starDetectionThresholdLuma()},
              "star_detection_max_blob_pixels": ${settings.starDetectionMaxBlobPixels()},
              "stars_detected": ${ssimResult.starsDetected},
              "star_count": ${ssimResult.starCount},
              "ssim_suppressed_by_stars": ${ssimResult.suppressedByStars},
              "star_threshold_luma": ${ssimResult.starThresholdLuma ?: "null"},
              "star_background_luma": ${ssimResult.starBackgroundLuma ?: "null"},
              "low_noise_iso": ${settings.lowNoiseIso()},
              "image_brightness": ${imageBrightness?.toString() ?: "null"},
              "iso": ${captureExif.iso?.toString() ?: "null"},
              "exposure_time": ${captureExif.exposureTimeSeconds?.toString() ?: "null"},
              "shutter_speed": ${captureExif.shutterSpeed?.let { jsonString(it) } ?: "null"},
              "white_balance": ${captureExif.whiteBalance?.let { jsonString(it) } ?: "null"},
              "timestamp_overlay": ${settings.timestampOverlayEnabled()},
              "timestamp_overlay_position": ${jsonString(settings.timestampOverlayPosition().name.lowercase())},
              "sun_path_overlay": ${settings.sunPathOverlayEnabled()},
              "sun_path_overlay_position": ${jsonString(settings.sunPathOverlayPosition().name.lowercase())},
              "overlay_timezone": ${jsonString(settings.overlayTimezone())},
              "overlay_lat": ${settings.overlayLatitude()},
              "overlay_lon": ${settings.overlayLongitude()},
              "captured_at_ms": ${System.currentTimeMillis()}
            }
            """.trimIndent() + "\n"
        )
    }

    fun rootPath(): String = rootDir.absolutePath

    fun rootDir(): File = rootDir

    private fun jsonString(value: String): String {
        return buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
        }
    }

    companion object {
        private val DAY_DIR_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
