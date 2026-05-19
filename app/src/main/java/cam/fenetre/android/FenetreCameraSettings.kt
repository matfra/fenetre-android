package cam.fenetre.android

import android.content.Context
import android.os.Build
import kotlin.math.roundToLong

enum class LensMode(val label: String) {
    ULTRA_WIDE("Ultra wide"),
    WIDE("Wide"),
    TELE("Tele"),
}

enum class ExposureMode(val label: String) {
    AUTO("Adaptive low ISO"),
    PHONE_AUTO("Phone auto"),
}

enum class DailyTimelapseEncoderMode(val label: String, val fileExtension: String) {
    H264_FAST("H.264 fast", "mp4"),
    VP9_HIGH_QUALITY("VP9 high quality", "webm"),
}

enum class ThermalStatusThreshold(val value: Int, val label: String) {
    MANUAL(0, "Manual temperature only"),
    LIGHT(1, "Light (1)"),
    MODERATE(2, "Moderate (2)"),
    SEVERE(3, "Severe (3)"),
    CRITICAL(4, "Critical (4)"),
    EMERGENCY(5, "Emergency (5)"),
}

enum class NightCaptureStrategy(val label: String) {
    MANUAL_ADAPTIVE("Manual adaptive exposure"),
    CAMERA2_NIGHT_SCENE("Camera2 night scene mode"),
    CAMERAX_NIGHT_EXTENSION("CameraX night extension"),
}

class FenetreCameraSettings(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = context.getSharedPreferences("fenetre_camera", Context.MODE_PRIVATE)

    fun lensMode(): LensMode {
        val name = preferences.getString(KEY_LENS_MODE, LensMode.ULTRA_WIDE.name)
        return LensMode.entries.firstOrNull { it.name == name } ?: LensMode.ULTRA_WIDE
    }

    fun setLensMode(mode: LensMode) {
        preferences.edit().putString(KEY_LENS_MODE, mode.name).apply()
    }

    fun rotationDegrees(): Int = preferences.getInt(KEY_ROTATION_DEGREES, DEFAULT_ROTATION_DEGREES)

    fun setRotationDegrees(degrees: Int) {
        val normalized = when (degrees) {
            0, 90, 180, 270 -> degrees
            else -> DEFAULT_ROTATION_DEGREES
        }
        preferences.edit().putInt(KEY_ROTATION_DEGREES, normalized).apply()
    }

    fun exposureMode(): ExposureMode {
        val name = preferences.getString(KEY_EXPOSURE_MODE, ExposureMode.AUTO.name)
        return ExposureMode.entries.firstOrNull { it.name == name } ?: ExposureMode.AUTO
    }

    fun setExposureMode(mode: ExposureMode) {
        preferences.edit().putString(KEY_EXPOSURE_MODE, mode.name).apply()
    }

    fun cameraName(): String = cleanPathSegment(preferences.getString(KEY_CAMERA_NAME, null), defaultCameraName())

    fun setCameraName(value: String) {
        preferences.edit().putString(KEY_CAMERA_NAME, cleanPathSegment(value, defaultCameraName())).apply()
    }

    fun deploymentName(): String = cleanText(
        preferences.getString(KEY_DEPLOYMENT_NAME, DEFAULT_DEPLOYMENT_NAME),
        DEFAULT_DEPLOYMENT_NAME,
    )

    fun setDeploymentName(value: String) {
        preferences.edit().putString(KEY_DEPLOYMENT_NAME, cleanText(value, DEFAULT_DEPLOYMENT_NAME)).apply()
    }

    fun publicBaseUrl(): String = normalizeUrl(
        preferences.getString(KEY_PUBLIC_BASE_URL, DEFAULT_PUBLIC_BASE_URL),
        DEFAULT_PUBLIC_BASE_URL,
    )

    fun setPublicBaseUrl(value: String) {
        preferences.edit().putString(KEY_PUBLIC_BASE_URL, normalizeUrl(value, DEFAULT_PUBLIC_BASE_URL)).apply()
    }

    fun cameraDescription(): String = cleanText(
        preferences.getString(KEY_CAMERA_DESCRIPTION, DEFAULT_CAMERA_DESCRIPTION),
        DEFAULT_CAMERA_DESCRIPTION,
    )

    fun setCameraDescription(value: String) {
        preferences.edit().putString(KEY_CAMERA_DESCRIPTION, cleanText(value, DEFAULT_CAMERA_DESCRIPTION)).apply()
    }

    fun comparisonUrl(): String = normalizeUrl(
        preferences.getString(KEY_COMPARISON_URL, DEFAULT_COMPARISON_URL),
        DEFAULT_COMPARISON_URL,
    )

    fun setComparisonUrl(value: String) {
        preferences.edit().putString(KEY_COMPARISON_URL, normalizeUrl(value, DEFAULT_COMPARISON_URL)).apply()
    }

    fun canonicalWebsiteLinkEnabled(): Boolean = preferences.getBoolean(
        KEY_CANONICAL_WEBSITE_LINK_ENABLED,
        DEFAULT_CANONICAL_WEBSITE_LINK_ENABLED,
    )

    fun setCanonicalWebsiteLinkEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_CANONICAL_WEBSITE_LINK_ENABLED, value).apply()
    }

    fun webHost(): String = cleanText(preferences.getString(KEY_WEB_HOST, DEFAULT_WEB_HOST), DEFAULT_WEB_HOST)

    fun setWebHost(value: String) {
        preferences.edit().putString(KEY_WEB_HOST, cleanText(value, DEFAULT_WEB_HOST)).apply()
    }

    fun webPort(): Int = preferences.getInt(KEY_WEB_PORT, DEFAULT_WEB_PORT).coerceIn(1024, 65535)

    fun setWebPort(value: Int) {
        preferences.edit().putInt(KEY_WEB_PORT, value.coerceIn(1024, 65535)).apply()
    }

    fun adminPort(): Int = preferences.getInt(KEY_ADMIN_PORT, DEFAULT_ADMIN_PORT).coerceIn(1024, 65535)

    fun setAdminPort(value: Int) {
        preferences.edit().putInt(KEY_ADMIN_PORT, value.coerceIn(1024, 65535)).apply()
    }

    fun captureIntervalSeconds(): Int = preferences.getInt(
        KEY_CAPTURE_INTERVAL_SECONDS,
        DEFAULT_CAPTURE_INTERVAL_SECONDS,
    ).coerceIn(5, 3600)

    fun setCaptureIntervalSeconds(value: Int) {
        preferences.edit().putInt(KEY_CAPTURE_INTERVAL_SECONDS, value.coerceIn(5, 3600)).apply()
    }

    fun dailyTimelapseEncoderMode(): DailyTimelapseEncoderMode {
        val name = preferences.getString(KEY_DAILY_TIMELAPSE_ENCODER_MODE, DailyTimelapseEncoderMode.H264_FAST.name)
        return DailyTimelapseEncoderMode.entries.firstOrNull { it.name == name } ?: DailyTimelapseEncoderMode.H264_FAST
    }

    fun setDailyTimelapseEncoderMode(mode: DailyTimelapseEncoderMode) {
        preferences.edit().putString(KEY_DAILY_TIMELAPSE_ENCODER_MODE, mode.name).apply()
    }

    fun dailyVp9BitrateMbps(): Double = preferences.getFloat(
        KEY_DAILY_VP9_BITRATE_MEGABITS,
        DEFAULT_DAILY_VP9_BITRATE_MEGABITS.toFloat(),
    ).toDouble().coerceIn(1.0, 50.0)

    fun dailyVp9BitrateBitsPerSecond(): Int = (dailyVp9BitrateMbps() * 1_000_000.0).roundToLong().toInt()

    fun setDailyVp9BitrateMbps(value: Double) {
        preferences.edit().putFloat(KEY_DAILY_VP9_BITRATE_MEGABITS, value.coerceIn(1.0, 50.0).toFloat()).apply()
    }

    fun ffmpegExecutablePath(): String {
        return preferences.getString(KEY_FFMPEG_EXECUTABLE_PATH, "")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: bundledFfmpegExecutablePath()
    }

    fun setFfmpegExecutablePath(value: String) {
        preferences.edit().putString(KEY_FFMPEG_EXECUTABLE_PATH, value.trim()).apply()
    }

    fun bundledFfmpegExecutablePath(): String = FenetreBundledFfmpeg.executablePath(appContext)

    fun cooldownEnabled(): Boolean = preferences.getBoolean(KEY_COOLDOWN_ENABLED, DEFAULT_COOLDOWN_ENABLED)

    fun setCooldownEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_COOLDOWN_ENABLED, value).apply()
    }

    fun cooldownBatteryTemperatureCelsius(): Double = preferences.getFloat(
        KEY_COOLDOWN_BATTERY_TEMPERATURE_CELSIUS,
        DEFAULT_COOLDOWN_BATTERY_TEMPERATURE_CELSIUS.toFloat(),
    ).toDouble().coerceIn(30.0, 70.0)

    fun setCooldownBatteryTemperatureCelsius(value: Double) {
        preferences.edit()
            .putFloat(KEY_COOLDOWN_BATTERY_TEMPERATURE_CELSIUS, value.coerceIn(30.0, 70.0).toFloat())
            .apply()
    }

    fun cooldownThermalStatusThreshold(): ThermalStatusThreshold {
        val value = preferences.getInt(KEY_COOLDOWN_THERMAL_STATUS_THRESHOLD, DEFAULT_COOLDOWN_THERMAL_STATUS_THRESHOLD)
        return ThermalStatusThreshold.entries.firstOrNull { it.value == value } ?: ThermalStatusThreshold.SEVERE
    }

    fun setCooldownThermalStatusThreshold(value: ThermalStatusThreshold) {
        preferences.edit().putInt(KEY_COOLDOWN_THERMAL_STATUS_THRESHOLD, value.value).apply()
    }

    fun storageManagementEnabled(): Boolean = preferences.getBoolean(
        KEY_STORAGE_MANAGEMENT_ENABLED,
        DEFAULT_STORAGE_MANAGEMENT_ENABLED,
    )

    fun setStorageManagementEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_STORAGE_MANAGEMENT_ENABLED, value).apply()
    }

    fun storageManagementDryRun(): Boolean = preferences.getBoolean(
        KEY_STORAGE_MANAGEMENT_DRY_RUN,
        DEFAULT_STORAGE_MANAGEMENT_DRY_RUN,
    )

    fun setStorageManagementDryRun(value: Boolean) {
        preferences.edit().putBoolean(KEY_STORAGE_MANAGEMENT_DRY_RUN, value).apply()
    }

    fun storageManagementCheckIntervalSeconds(): Int = preferences.getInt(
        KEY_STORAGE_MANAGEMENT_CHECK_INTERVAL_SECONDS,
        DEFAULT_STORAGE_MANAGEMENT_CHECK_INTERVAL_SECONDS,
    ).coerceIn(60, 86_400)

    fun setStorageManagementCheckIntervalSeconds(value: Int) {
        preferences.edit().putInt(KEY_STORAGE_MANAGEMENT_CHECK_INTERVAL_SECONDS, value.coerceIn(60, 86_400)).apply()
    }

    fun storageManagementMaxSizeGb(): Int = preferences.getInt(
        KEY_STORAGE_MANAGEMENT_MAX_SIZE_GB,
        DEFAULT_STORAGE_MANAGEMENT_MAX_SIZE_GB,
    ).coerceIn(1, 1024)

    fun setStorageManagementMaxSizeGb(value: Int) {
        preferences.edit().putInt(KEY_STORAGE_MANAGEMENT_MAX_SIZE_GB, value.coerceIn(1, 1024)).apply()
    }

    fun storageArchiveEnabled(): Boolean = preferences.getBoolean(
        KEY_STORAGE_ARCHIVE_ENABLED,
        DEFAULT_STORAGE_ARCHIVE_ENABLED,
    )

    fun setStorageArchiveEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_STORAGE_ARCHIVE_ENABLED, value).apply()
    }

    fun storageArchiveAfterDays(): Int = preferences.getInt(
        KEY_STORAGE_ARCHIVE_AFTER_DAYS,
        DEFAULT_STORAGE_ARCHIVE_AFTER_DAYS,
    ).coerceIn(1, 3650)

    fun setStorageArchiveAfterDays(value: Int) {
        preferences.edit().putInt(KEY_STORAGE_ARCHIVE_AFTER_DAYS, value.coerceIn(1, 3650)).apply()
    }

    fun storageArchiveFilesToKeep(): Int = preferences.getInt(
        KEY_STORAGE_ARCHIVE_FILES_TO_KEEP,
        DEFAULT_STORAGE_ARCHIVE_FILES_TO_KEEP,
    ).coerceIn(1, 10_000)

    fun setStorageArchiveFilesToKeep(value: Int) {
        preferences.edit().putInt(KEY_STORAGE_ARCHIVE_FILES_TO_KEEP, value.coerceIn(1, 10_000)).apply()
    }

    fun sunriseSunsetFastEnabled(): Boolean = preferences.getBoolean(
        KEY_SUNRISE_SUNSET_FAST_ENABLED,
        DEFAULT_SUNRISE_SUNSET_FAST_ENABLED,
    )

    fun setSunriseSunsetFastEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_SUNRISE_SUNSET_FAST_ENABLED, value).apply()
    }

    fun sunriseSunsetFastIntervalSeconds(): Int = preferences.getInt(
        KEY_SUNRISE_SUNSET_FAST_INTERVAL_SECONDS,
        DEFAULT_SUNRISE_SUNSET_FAST_INTERVAL_SECONDS,
    ).coerceIn(1, 3600)

    fun setSunriseSunsetFastIntervalSeconds(value: Int) {
        preferences.edit().putInt(KEY_SUNRISE_SUNSET_FAST_INTERVAL_SECONDS, value.coerceIn(1, 3600)).apply()
    }

    fun sunriseOffsetStartMinutes(): Int = preferences.getInt(
        KEY_SUNRISE_OFFSET_START_MINUTES,
        DEFAULT_SUNRISE_OFFSET_START_MINUTES,
    ).coerceIn(0, 360)

    fun setSunriseOffsetStartMinutes(value: Int) {
        preferences.edit().putInt(KEY_SUNRISE_OFFSET_START_MINUTES, value.coerceIn(0, 360)).apply()
    }

    fun sunriseOffsetEndMinutes(): Int = preferences.getInt(
        KEY_SUNRISE_OFFSET_END_MINUTES,
        DEFAULT_SUNRISE_OFFSET_END_MINUTES,
    ).coerceIn(0, 360)

    fun setSunriseOffsetEndMinutes(value: Int) {
        preferences.edit().putInt(KEY_SUNRISE_OFFSET_END_MINUTES, value.coerceIn(0, 360)).apply()
    }

    fun sunsetOffsetStartMinutes(): Int = preferences.getInt(
        KEY_SUNSET_OFFSET_START_MINUTES,
        DEFAULT_SUNSET_OFFSET_START_MINUTES,
    ).coerceIn(0, 360)

    fun setSunsetOffsetStartMinutes(value: Int) {
        preferences.edit().putInt(KEY_SUNSET_OFFSET_START_MINUTES, value.coerceIn(0, 360)).apply()
    }

    fun sunsetOffsetEndMinutes(): Int = preferences.getInt(
        KEY_SUNSET_OFFSET_END_MINUTES,
        DEFAULT_SUNSET_OFFSET_END_MINUTES,
    ).coerceIn(0, 360)

    fun setSunsetOffsetEndMinutes(value: Int) {
        preferences.edit().putInt(KEY_SUNSET_OFFSET_END_MINUTES, value.coerceIn(0, 360)).apply()
    }

    fun nightExposureBoostStops(): Double = preferences.getFloat(
        KEY_NIGHT_EXPOSURE_BOOST_STOPS,
        DEFAULT_NIGHT_EXPOSURE_BOOST_STOPS.toFloat(),
    ).toDouble().coerceIn(0.0, 4.0)

    fun setNightExposureBoostStops(value: Double) {
        preferences.edit().putFloat(KEY_NIGHT_EXPOSURE_BOOST_STOPS, value.coerceIn(0.0, 4.0).toFloat()).apply()
    }

    fun nightExposureBoostTwilightBufferMinutes(): Int = preferences.getInt(
        KEY_NIGHT_EXPOSURE_BOOST_TWILIGHT_BUFFER_MINUTES,
        DEFAULT_NIGHT_EXPOSURE_BOOST_TWILIGHT_BUFFER_MINUTES,
    ).coerceIn(0, 360)

    fun setNightExposureBoostTwilightBufferMinutes(value: Int) {
        preferences.edit().putInt(KEY_NIGHT_EXPOSURE_BOOST_TWILIGHT_BUFFER_MINUTES, value.coerceIn(0, 360)).apply()
    }

    fun nightCaptureStrategy(): NightCaptureStrategy {
        val name = preferences.getString(KEY_NIGHT_CAPTURE_STRATEGY, DEFAULT_NIGHT_CAPTURE_STRATEGY.name)
        return NightCaptureStrategy.entries.firstOrNull { it.name == name } ?: DEFAULT_NIGHT_CAPTURE_STRATEGY
    }

    fun setNightCaptureStrategy(value: NightCaptureStrategy) {
        preferences.edit().putString(KEY_NIGHT_CAPTURE_STRATEGY, value.name).apply()
    }

    fun dayExposureCompositeThreshold(): Double = preferences.getFloat(
        KEY_DAY_EXPOSURE_COMPOSITE_THRESHOLD,
        DEFAULT_DAY_EXPOSURE_COMPOSITE_THRESHOLD.toFloat(),
    ).toDouble().coerceIn(0.0, 10_000.0)

    fun setDayExposureCompositeThreshold(value: Double) {
        preferences.edit()
            .putFloat(KEY_DAY_EXPOSURE_COMPOSITE_THRESHOLD, value.coerceIn(0.0, 10_000.0).toFloat())
            .apply()
    }

    fun nightExposureCompositeThreshold(): Double = preferences.getFloat(
        KEY_NIGHT_EXPOSURE_COMPOSITE_THRESHOLD,
        DEFAULT_NIGHT_EXPOSURE_COMPOSITE_THRESHOLD.toFloat(),
    ).toDouble().coerceIn(0.0, 10_000.0)

    fun setNightExposureCompositeThreshold(value: Double) {
        preferences.edit()
            .putFloat(KEY_NIGHT_EXPOSURE_COMPOSITE_THRESHOLD, value.coerceIn(0.0, 10_000.0).toFloat())
            .apply()
    }

    fun manualNightTargetLuma(): Double = preferences.getFloat(
        KEY_MANUAL_NIGHT_TARGET_LUMA,
        DEFAULT_MANUAL_NIGHT_TARGET_LUMA.toFloat(),
    ).toDouble().coerceIn(0.01, 0.8)

    fun setManualNightTargetLuma(value: Double) {
        preferences.edit()
            .putFloat(KEY_MANUAL_NIGHT_TARGET_LUMA, value.coerceIn(0.01, 0.8).toFloat())
            .apply()
    }

    fun vignetteCorrectionEnabled(): Boolean = preferences.getBoolean(
        KEY_VIGNETTE_CORRECTION_ENABLED,
        defaultVignetteCorrectionEnabled(),
    )

    fun setVignetteCorrectionEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_VIGNETTE_CORRECTION_ENABLED, value).apply()
    }

    fun vignetteCorrectionStrength(): Double = preferences.getFloat(
        KEY_VIGNETTE_CORRECTION_STRENGTH,
        DEFAULT_VIGNETTE_CORRECTION_STRENGTH.toFloat(),
    ).toDouble().coerceIn(0.0, 8.0)

    fun setVignetteCorrectionStrength(value: Double) {
        preferences.edit()
            .putFloat(KEY_VIGNETTE_CORRECTION_STRENGTH, value.coerceIn(0.0, 8.0).toFloat())
            .apply()
    }

    fun vignetteCorrectionPower(): Double = preferences.getFloat(
        KEY_VIGNETTE_CORRECTION_POWER,
        DEFAULT_VIGNETTE_CORRECTION_POWER.toFloat(),
    ).toDouble().coerceIn(0.5, 4.0)

    fun setVignetteCorrectionPower(value: Double) {
        preferences.edit()
            .putFloat(KEY_VIGNETTE_CORRECTION_POWER, value.coerceIn(0.5, 4.0).toFloat())
            .apply()
    }

    fun vignetteCorrectionRadius(): Double = preferences.getFloat(
        KEY_VIGNETTE_CORRECTION_RADIUS,
        DEFAULT_VIGNETTE_CORRECTION_RADIUS.toFloat(),
    ).toDouble().coerceIn(0.1, 1.0)

    fun setVignetteCorrectionRadius(value: Double) {
        preferences.edit()
            .putFloat(KEY_VIGNETTE_CORRECTION_RADIUS, value.coerceIn(0.1, 1.0).toFloat())
            .apply()
    }

    private fun defaultVignetteCorrectionEnabled(): Boolean {
        return Build.MANUFACTURER.equals("google", ignoreCase = true) &&
            Build.MODEL.equals("Pixel 6 Pro", ignoreCase = true)
    }

    fun focusInfinityEnabled(): Boolean = preferences.getBoolean(
        KEY_FOCUS_INFINITY_ENABLED,
        DEFAULT_FOCUS_INFINITY_ENABLED,
    )

    fun setFocusInfinityEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_FOCUS_INFINITY_ENABLED, value).apply()
    }

    fun lowNoiseIso(): Int = preferences.getInt(KEY_LOW_NOISE_ISO, DEFAULT_LOW_NOISE_ISO).coerceIn(25, 6400)

    fun setLowNoiseIso(value: Int) {
        preferences.edit().putInt(KEY_LOW_NOISE_ISO, value.coerceIn(25, 6400)).apply()
    }

    fun maxExposureSeconds(mode: LensMode): Double {
        val key = when (mode) {
            LensMode.ULTRA_WIDE -> KEY_ULTRA_WIDE_NIGHT_EXPOSURE_SECONDS
            LensMode.WIDE -> KEY_WIDE_NIGHT_EXPOSURE_SECONDS
            LensMode.TELE -> KEY_TELE_NIGHT_EXPOSURE_SECONDS
        }
        val defaultValue = when (mode) {
            LensMode.ULTRA_WIDE -> DEFAULT_ULTRA_WIDE_NIGHT_EXPOSURE_SECONDS
            LensMode.WIDE -> DEFAULT_WIDE_NIGHT_EXPOSURE_SECONDS
            LensMode.TELE -> DEFAULT_TELE_NIGHT_EXPOSURE_SECONDS
        }
        return preferences.getFloat(key, defaultValue.toFloat()).toDouble().coerceIn(0.1, 60.0)
    }

    fun setMaxExposureSeconds(mode: LensMode, value: Double) {
        val key = when (mode) {
            LensMode.ULTRA_WIDE -> KEY_ULTRA_WIDE_NIGHT_EXPOSURE_SECONDS
            LensMode.WIDE -> KEY_WIDE_NIGHT_EXPOSURE_SECONDS
            LensMode.TELE -> KEY_TELE_NIGHT_EXPOSURE_SECONDS
        }
        preferences.edit().putFloat(key, value.coerceIn(0.1, 60.0).toFloat()).apply()
    }

    fun maxExposureNs(mode: LensMode): Long = (maxExposureSeconds(mode) * 1_000_000_000.0).roundToLong()

    fun timestampOverlayEnabled(): Boolean = preferences.getBoolean(
        KEY_TIMESTAMP_OVERLAY_ENABLED,
        DEFAULT_TIMESTAMP_OVERLAY_ENABLED,
    )

    fun setTimestampOverlayEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_TIMESTAMP_OVERLAY_ENABLED, value).apply()
    }

    fun sunPathOverlayEnabled(): Boolean = preferences.getBoolean(
        KEY_SUN_PATH_OVERLAY_ENABLED,
        DEFAULT_SUN_PATH_OVERLAY_ENABLED,
    )

    fun setSunPathOverlayEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_SUN_PATH_OVERLAY_ENABLED, value).apply()
    }

    fun overlayTimezone(): String = cleanText(
        preferences.getString(KEY_OVERLAY_TIMEZONE, DEFAULT_OVERLAY_TIMEZONE),
        DEFAULT_OVERLAY_TIMEZONE,
    )

    fun setOverlayTimezone(value: String) {
        preferences.edit().putString(KEY_OVERLAY_TIMEZONE, cleanText(value, DEFAULT_OVERLAY_TIMEZONE)).apply()
    }

    fun overlayLatitude(): Double = preferences.getFloat(
        KEY_OVERLAY_LATITUDE,
        DEFAULT_OVERLAY_LATITUDE.toFloat(),
    ).toDouble().coerceIn(-90.0, 90.0)

    fun setOverlayLatitude(value: Double) {
        preferences.edit().putFloat(KEY_OVERLAY_LATITUDE, value.coerceIn(-90.0, 90.0).toFloat()).apply()
    }

    fun overlayLongitude(): Double = preferences.getFloat(
        KEY_OVERLAY_LONGITUDE,
        DEFAULT_OVERLAY_LONGITUDE.toFloat(),
    ).toDouble().coerceIn(-180.0, 180.0)

    fun setOverlayLongitude(value: Double) {
        preferences.edit().putFloat(KEY_OVERLAY_LONGITUDE, value.coerceIn(-180.0, 180.0).toFloat()).apply()
    }

    fun localWebUrl(): String = "http://${webHost()}:${webPort()}/"

    private fun cleanText(value: String?, fallback: String): String {
        return value?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
    }

    private fun cleanPathSegment(value: String?, fallback: String): String {
        val cleaned = cleanText(value, fallback).replace(Regex("""[^A-Za-z0-9._-]"""), "-").trim('-')
        return cleaned.ifEmpty { fallback }
    }

    private fun defaultCameraName(): String {
        val model = Build.MODEL?.lowercase()?.replace(Regex("""[^a-z0-9._-]"""), "-")?.trim('-')
        return model?.takeIf { it.isNotEmpty() } ?: DEFAULT_CAMERA_NAME_FALLBACK
    }

    private fun normalizeUrl(value: String?, fallback: String): String {
        val cleaned = cleanText(value, fallback)
        if (cleaned.contains("?") || cleaned.contains("#")) {
            return cleaned
        }
        return if (cleaned.endsWith("/")) cleaned else "$cleaned/"
    }

    companion object {
        private const val KEY_LENS_MODE = "lens_mode"
        private const val KEY_ROTATION_DEGREES = "rotation_degrees"
        private const val KEY_EXPOSURE_MODE = "exposure_mode"
        private const val KEY_CAMERA_NAME = "camera_name"
        private const val KEY_DEPLOYMENT_NAME = "deployment_name"
        private const val KEY_PUBLIC_BASE_URL = "public_base_url"
        private const val KEY_CAMERA_DESCRIPTION = "camera_description"
        private const val KEY_COMPARISON_URL = "comparison_url"
        private const val KEY_CANONICAL_WEBSITE_LINK_ENABLED = "canonical_website_link_enabled"
        private const val KEY_WEB_HOST = "web_host"
        private const val KEY_WEB_PORT = "web_port"
        private const val KEY_ADMIN_PORT = "admin_port"
        private const val KEY_CAPTURE_INTERVAL_SECONDS = "capture_interval_seconds"
        private const val KEY_DAILY_TIMELAPSE_ENCODER_MODE = "daily_timelapse_encoder_mode"
        private const val KEY_DAILY_VP9_BITRATE_MEGABITS = "daily_vp9_bitrate_megabits"
        private const val KEY_FFMPEG_EXECUTABLE_PATH = "ffmpeg_executable_path"
        private const val KEY_COOLDOWN_ENABLED = "cooldown_enabled"
        private const val KEY_COOLDOWN_BATTERY_TEMPERATURE_CELSIUS = "cooldown_battery_temperature_celsius"
        private const val KEY_COOLDOWN_THERMAL_STATUS_THRESHOLD = "cooldown_thermal_status_threshold"
        private const val KEY_STORAGE_MANAGEMENT_ENABLED = "storage_management_enabled"
        private const val KEY_STORAGE_MANAGEMENT_DRY_RUN = "storage_management_dry_run"
        private const val KEY_STORAGE_MANAGEMENT_CHECK_INTERVAL_SECONDS = "storage_management_check_interval_seconds"
        private const val KEY_STORAGE_MANAGEMENT_MAX_SIZE_GB = "storage_management_max_size_gb"
        private const val KEY_STORAGE_ARCHIVE_ENABLED = "storage_archive_enabled"
        private const val KEY_STORAGE_ARCHIVE_AFTER_DAYS = "storage_archive_after_days"
        private const val KEY_STORAGE_ARCHIVE_FILES_TO_KEEP = "storage_archive_files_to_keep"
        private const val KEY_SUNRISE_SUNSET_FAST_ENABLED = "sunrise_sunset_fast_enabled"
        private const val KEY_SUNRISE_SUNSET_FAST_INTERVAL_SECONDS = "sunrise_sunset_fast_interval_seconds"
        private const val KEY_SUNRISE_OFFSET_START_MINUTES = "sunrise_offset_start_minutes"
        private const val KEY_SUNRISE_OFFSET_END_MINUTES = "sunrise_offset_end_minutes"
        private const val KEY_SUNSET_OFFSET_START_MINUTES = "sunset_offset_start_minutes"
        private const val KEY_SUNSET_OFFSET_END_MINUTES = "sunset_offset_end_minutes"
        private const val KEY_NIGHT_EXPOSURE_BOOST_STOPS = "night_exposure_boost_stops"
        private const val KEY_NIGHT_EXPOSURE_BOOST_TWILIGHT_BUFFER_MINUTES = "night_exposure_boost_twilight_buffer_minutes"
        private const val KEY_NIGHT_CAPTURE_STRATEGY = "night_capture_strategy"
        private const val KEY_DAY_EXPOSURE_COMPOSITE_THRESHOLD = "day_exposure_composite_threshold"
        private const val KEY_NIGHT_EXPOSURE_COMPOSITE_THRESHOLD = "night_exposure_composite_threshold"
        private const val KEY_MANUAL_NIGHT_TARGET_LUMA = "manual_night_target_luma"
        private const val KEY_VIGNETTE_CORRECTION_ENABLED = "vignette_correction_enabled"
        private const val KEY_VIGNETTE_CORRECTION_STRENGTH = "vignette_correction_strength"
        private const val KEY_VIGNETTE_CORRECTION_POWER = "vignette_correction_power"
        private const val KEY_VIGNETTE_CORRECTION_RADIUS = "vignette_correction_radius"
        private const val KEY_FOCUS_INFINITY_ENABLED = "focus_infinity_enabled"
        private const val KEY_LOW_NOISE_ISO = "low_noise_iso"
        private const val KEY_ULTRA_WIDE_NIGHT_EXPOSURE_SECONDS = "ultra_wide_night_exposure_seconds"
        private const val KEY_WIDE_NIGHT_EXPOSURE_SECONDS = "wide_night_exposure_seconds"
        private const val KEY_TELE_NIGHT_EXPOSURE_SECONDS = "tele_night_exposure_seconds"
        private const val KEY_TIMESTAMP_OVERLAY_ENABLED = "timestamp_overlay_enabled"
        private const val KEY_SUN_PATH_OVERLAY_ENABLED = "sun_path_overlay_enabled"
        private const val KEY_OVERLAY_TIMEZONE = "overlay_timezone"
        private const val KEY_OVERLAY_LATITUDE = "overlay_latitude"
        private const val KEY_OVERLAY_LONGITUDE = "overlay_longitude"
        private const val DEFAULT_ROTATION_DEGREES = 0
        private const val DEFAULT_CAMERA_NAME_FALLBACK = "android-camera"
        private const val DEFAULT_DEPLOYMENT_NAME = "p6p.fenetre.cam"
        private const val DEFAULT_PUBLIC_BASE_URL = "https://p6p.fenetre.cam/"
        private const val DEFAULT_CAMERA_DESCRIPTION = "Pixel 6 Pro Android camera"
        private const val DEFAULT_COMPARISON_URL = "https://dev.fenetre.cam/fullscreen.html?camera=gopro-hero-6"
        private const val DEFAULT_CANONICAL_WEBSITE_LINK_ENABLED = true
        private const val DEFAULT_WEB_HOST = "192.168.8.242"
        private const val DEFAULT_WEB_PORT = 8888
        private const val DEFAULT_ADMIN_PORT = 8889
        private const val DEFAULT_CAPTURE_INTERVAL_SECONDS = 30
        private const val DEFAULT_DAILY_VP9_BITRATE_MEGABITS = 7.0
        private const val DEFAULT_COOLDOWN_ENABLED = true
        private const val DEFAULT_COOLDOWN_BATTERY_TEMPERATURE_CELSIUS = 45.0
        private const val DEFAULT_COOLDOWN_THERMAL_STATUS_THRESHOLD = 3
        private const val DEFAULT_STORAGE_MANAGEMENT_ENABLED = false
        private const val DEFAULT_STORAGE_MANAGEMENT_DRY_RUN = true
        private const val DEFAULT_STORAGE_MANAGEMENT_CHECK_INTERVAL_SECONDS = 300
        private const val DEFAULT_STORAGE_MANAGEMENT_MAX_SIZE_GB = 10
        private const val DEFAULT_STORAGE_ARCHIVE_ENABLED = true
        private const val DEFAULT_STORAGE_ARCHIVE_AFTER_DAYS = 3
        private const val DEFAULT_STORAGE_ARCHIVE_FILES_TO_KEEP = 48
        private const val DEFAULT_SUNRISE_SUNSET_FAST_ENABLED = false
        private const val DEFAULT_SUNRISE_SUNSET_FAST_INTERVAL_SECONDS = 10
        private const val DEFAULT_SUNRISE_OFFSET_START_MINUTES = 60
        private const val DEFAULT_SUNRISE_OFFSET_END_MINUTES = 30
        private const val DEFAULT_SUNSET_OFFSET_START_MINUTES = 30
        private const val DEFAULT_SUNSET_OFFSET_END_MINUTES = 60
        private const val DEFAULT_NIGHT_EXPOSURE_BOOST_STOPS = 0.0
        private const val DEFAULT_NIGHT_EXPOSURE_BOOST_TWILIGHT_BUFFER_MINUTES = 90
        private val DEFAULT_NIGHT_CAPTURE_STRATEGY = NightCaptureStrategy.MANUAL_ADAPTIVE
        private const val DEFAULT_DAY_EXPOSURE_COMPOSITE_THRESHOLD = 1.0
        private const val DEFAULT_NIGHT_EXPOSURE_COMPOSITE_THRESHOLD = 2.0
        private const val DEFAULT_MANUAL_NIGHT_TARGET_LUMA = 0.12
        private const val DEFAULT_VIGNETTE_CORRECTION_STRENGTH = 3.5
        private const val DEFAULT_VIGNETTE_CORRECTION_POWER = 2.0
        private const val DEFAULT_VIGNETTE_CORRECTION_RADIUS = 0.65
        private const val DEFAULT_FOCUS_INFINITY_ENABLED = true
        private const val DEFAULT_LOW_NOISE_ISO = 100
        private const val DEFAULT_ULTRA_WIDE_NIGHT_EXPOSURE_SECONDS = 25.0
        private const val DEFAULT_WIDE_NIGHT_EXPOSURE_SECONDS = 15.0
        private const val DEFAULT_TELE_NIGHT_EXPOSURE_SECONDS = 5.0
        private const val DEFAULT_TIMESTAMP_OVERLAY_ENABLED = true
        private const val DEFAULT_SUN_PATH_OVERLAY_ENABLED = true
        private const val DEFAULT_OVERLAY_TIMEZONE = "America/Los_Angeles"
        private const val DEFAULT_OVERLAY_LATITUDE = 37.6
        private const val DEFAULT_OVERLAY_LONGITUDE = -122.4
    }
}
