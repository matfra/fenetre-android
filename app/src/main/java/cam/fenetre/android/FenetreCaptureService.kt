package cam.fenetre.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class FenetreCaptureService : LifecycleService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var storage: FenetreStorage
    private lateinit var cameraSettings: FenetreCameraSettings
    private lateinit var timelapse: FenetreTimelapse
    private lateinit var daylight: FenetreDaylight
    private lateinit var storageManager: FenetreStorageManager
    private lateinit var overlays: FenetreOverlays
    private lateinit var sunSchedule: FenetreSunSchedule
    private var webServer: FenetreWebServer? = null
    private var adminServer: FenetreAdminServer? = null
    private var imageCapture: ImageCapture? = null
    private var lensMode = LensMode.ULTRA_WIDE
    private var rotationDegrees = 90
    private var exposureMode = ExposureMode.AUTO
    private var captureMode = ExposureMode.AUTO
    private var manualExposureSettings: ManualExposureSettings? = null
    private var captureInProgress = false
    private var captureGeneration = 0
    private var captureTimeoutRunnable: Runnable? = null
    private var cooldownRunnable: Runnable? = null
    private var lastNotification = "Starting"
    private var running = false

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
        cameraSettings = FenetreCameraSettings(this)
        FenetreBundledFfmpeg.installIfAvailable(this)
        storage = FenetreStorage(this, cameraSettings)
        timelapse = FenetreTimelapse(storage, cameraSettings)
        daylight = FenetreDaylight(storage)
        storageManager = FenetreStorageManager(storage, cameraSettings)
        overlays = FenetreOverlays(cameraSettings)
        sunSchedule = FenetreSunSchedule(cameraSettings)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            ACTION_CAPTURE_NOW -> captureOnce()
            ACTION_BUILD_DAILY_TIMELAPSE -> buildDailyTimelapse()
            ACTION_BUILD_DAYLIGHT -> buildDaylight()
            ACTION_RUN_STORAGE_MANAGEMENT -> runStorageManagement()
            else -> startCapture()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        serviceRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        timelapse.stop()
        daylight.stop()
        storageManager.stop()
        super.onDestroy()
        webServer?.stop()
        adminServer?.stop()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    private fun startCapture() {
        if (running) {
            return
        }
        running = true
        serviceRunning = true
        startServers()
        storageManager.maybeSchedule()
        startForeground(NOTIFICATION_ID, buildNotification("Starting capture"))
        bindCamera()
    }

    private fun stopCapture() {
        running = false
        serviceRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        captureInProgress = false
        serviceCaptureInProgress = false
        captureTimeoutRunnable = null
        cooldownRunnable = null
        webServer?.stop()
        webServer = null
        adminServer?.stop()
        adminServer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildDailyTimelapse() {
        startServers()
        if (pauseForCooldownIfNeeded()) {
            startForeground(NOTIFICATION_ID, buildNotification("Cooling down; daily timelapse paused"))
            return
        }
        startForeground(NOTIFICATION_ID, buildNotification("Building daily timelapse"))
        timelapse.scheduleDailyForCurrentDay()
        updateNotification("Daily timelapse queued; web: ${webServer?.url().orEmpty()}")
    }

    private fun buildDaylight() {
        startServers()
        if (pauseForCooldownIfNeeded()) {
            startForeground(NOTIFICATION_ID, buildNotification("Cooling down; daylight build paused"))
            return
        }
        startForeground(NOTIFICATION_ID, buildNotification("Building daylight bands"))
        daylight.scheduleFullRebuild()
        updateNotification("Daylight build queued; web: ${webServer?.url().orEmpty()}")
    }

    private fun runStorageManagement() {
        startServers()
        startForeground(NOTIFICATION_ID, buildNotification("Running storage management"))
        storageManager.maybeSchedule(force = true)
        updateNotification("Storage management queued; admin: ${adminServer?.url().orEmpty()}")
    }

    private fun startServers() {
        if (webServer == null) {
            webServer = FenetreWebServer(storage.rootDir(), cameraSettings).also { it.start() }
        }
        if (adminServer == null) {
            adminServer = FenetreAdminServer(this, storage.rootDir(), cameraSettings, runtimeStatus = {
                FenetreRuntimeStatus(
                    running = running,
                    captureInProgress = captureInProgress,
                    lensMode = lensMode,
                    exposureMode = exposureMode,
                    captureMode = captureMode,
                    rotationDegrees = rotationDegrees,
                    lastNotification = lastNotification,
                    thermalPaused = thermalStatus().paused,
                    storageManagement = storageManager.lastStatus(),
                )
            }).also { it.start() }
        }
    }

    private fun bindCamera(scheduleDelayMs: Long = 0L) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                val provider = providerFuture.get()
                lensMode = cameraSettings.lensMode()
                rotationDegrees = cameraSettings.rotationDegrees()
                exposureMode = cameraSettings.exposureMode()
                captureMode = resolvedCaptureMode()
                if (exposureMode != ExposureMode.AUTO) {
                    manualExposureSettings = null
                }
                val capture = buildImageCapture(captureMode)

                clearCaptureInProgress()
                provider.unbindAll()
                val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, capture)
                imageCapture = capture
                val zoomState = camera.cameraInfo.zoomState
                if (zoomState.value == null) {
                    zoomState.observe(this) { state -> applyLensMode(state, camera.cameraControl) }
                } else {
                    applyLensMode(zoomState.value, camera.cameraControl)
                }
                scheduleNextCapture(delayMs = scheduleDelayMs)
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun buildImageCapture(mode: ExposureMode): ImageCapture {
        val builder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(92)
            .setTargetRotation(Surface.ROTATION_90)
        val extender = Camera2Interop.Extender(builder)
        extender.setCaptureRequestOption(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
        )
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )
        if (mode == ExposureMode.AUTO && manualExposureSettings != null) {
            val settings = manualExposureSettings ?: return builder.build()
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_OFF
            )
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            extender.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, settings.exposureTimeNs)
            extender.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, settings.frameDurationNs)
            extender.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, settings.iso)
        }
        return builder.build()
    }

    private fun applyLensMode(zoomState: ZoomState?, cameraControl: androidx.camera.core.CameraControl) {
        val minZoom = zoomState?.minZoomRatio ?: 1.0f
        val maxZoom = zoomState?.maxZoomRatio ?: 1.0f
        val requestedZoom = when (lensMode) {
            LensMode.ULTRA_WIDE -> minZoom
            LensMode.WIDE -> 1.0f
            LensMode.TELE -> 4.0f
        }.coerceIn(minZoom, maxZoom)
        cameraControl.setZoomRatio(requestedZoom)
    }

    private fun scheduleNextCapture(delayMs: Long = sunSchedule.captureIntervalSeconds() * 1000L) {
        if (!running) {
            return
        }
        mainHandler.postDelayed({ captureOnce() }, delayMs)
    }

    private fun captureOnce() {
        val capture = imageCapture
        if (!running || capture == null) {
            return
        }
        if (pauseForCooldownIfNeeded()) {
            return
        }
        if (captureInProgress) {
            Log.w(TAG, "Skipping capture request; previous capture is still in progress")
            return
        }

        val photoFile = storage.nextPhotoFile()
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        val generation = beginCapture(photoFile)
        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (finishCapture(generation)) {
                        onCaptureSaved(photoFile)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    if (!finishCapture(generation)) {
                        return
                    }
                    Log.e(TAG, "Capture failed", exception)
                    updateNotification("Capture failed: ${exception.message ?: exception.imageCaptureError}")
                    scheduleNextCapture()
                }
            }
        )
    }

    private fun beginCapture(photoFile: File): Int {
        captureInProgress = true
        serviceCaptureInProgress = true
        captureGeneration += 1
        val generation = captureGeneration
        val timeoutMs = captureTimeoutMs()
        val timeoutRunnable = Runnable {
            if (!running || !captureInProgress || captureGeneration != generation) {
                return@Runnable
            }
            Log.w(TAG, "Capture timed out after ${timeoutMs}ms: ${photoFile.name}")
            captureInProgress = false
            serviceCaptureInProgress = false
            imageCapture = null
            if (photoFile.exists()) {
                photoFile.delete()
            }
            updateNotification("Capture timed out; rebinding camera")
            bindCamera()
        }
        captureTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, timeoutMs)
        return generation
    }

    private fun finishCapture(generation: Int): Boolean {
        if (!captureInProgress || captureGeneration != generation) {
            return false
        }
        clearCaptureInProgress()
        return true
    }

    private fun clearCaptureInProgress() {
        captureInProgress = false
        serviceCaptureInProgress = false
        captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        captureTimeoutRunnable = null
    }

    private fun captureTimeoutMs(): Long {
        val exposureMs = if (exposureMode == ExposureMode.AUTO) {
            manualExposureSettings?.frameDurationNs?.let { it / 1_000_000L } ?: 0L
        } else {
            0L
        }
        return maxOf(MIN_CAPTURE_TIMEOUT_MS, exposureMs * 3L + CAPTURE_TIMEOUT_PADDING_MS)
    }

    private fun onCaptureSaved(photoFile: File) {
        val captureExif = CaptureExif.fromFile(photoFile)
        val exposureComposite = captureExif.exposureComposite()
        val imageBrightness = averageNormalizedLuma(photoFile)
        JpegOrientation.normalize(photoFile, rotationDegrees)
        overlays.apply(photoFile)
        photoFile.copyTo(storage.latestFile(), overwrite = true)
        daylight.observe(photoFile)
        storage.writeMetadata(
            photoFile,
            lensMode,
            rotationDegrees,
            exposureMode,
            captureMode,
            manualExposureSettings,
            exposureComposite,
            imageBrightness,
            captureExif,
        )
        if (!pauseForCooldownIfNeeded()) {
            timelapse.scheduleFrequent()
            timelapse.scheduleDailyForCompletedDays()
            daylight.scheduleCompletedDays()
            storageManager.maybeSchedule()
        }
        updateNotification("Last capture: ${photoFile.name}; web: ${webServer?.url().orEmpty()}")
        val nextManualExposure = nextManualExposureSettings(captureExif, imageBrightness)
        if (nextManualExposure != manualExposureSettings) {
            manualExposureSettings = nextManualExposure
            mainHandler.post { bindCamera(scheduleDelayMs = sunSchedule.captureIntervalSeconds() * 1000L) }
        } else {
            scheduleNextCapture()
        }
    }

    private fun pauseForCooldownIfNeeded(): Boolean {
        val status = thermalStatus()
        if (!status.paused) {
            cooldownRunnable?.let { mainHandler.removeCallbacks(it) }
            cooldownRunnable = null
            return false
        }
        val temperatureText = status.batteryTemperatureCelsius?.let { String.format("%.1fC", it) } ?: "unknown temp"
        Log.w(
            TAG,
            "Thermal cooldown active: battery=$temperatureText threshold=${status.thresholdCelsius}C thermal=${status.androidThermalStatus}"
        )
        updateNotification("Cooling down: $temperatureText >= ${status.thresholdCelsius}C")
        scheduleCooldownCheck()
        return true
    }

    private fun scheduleCooldownCheck() {
        if (!running || cooldownRunnable != null) {
            return
        }
        val runnable = Runnable {
            cooldownRunnable = null
            captureOnce()
        }
        cooldownRunnable = runnable
        mainHandler.postDelayed(runnable, COOLDOWN_CHECK_INTERVAL_MS)
    }

    private fun thermalStatus(): FenetreThermalStatus = FenetreThermal.status(this, cameraSettings)

    private fun resolvedCaptureMode(): ExposureMode = exposureMode

    private fun nextManualExposureSettings(captureExif: CaptureExif, imageBrightness: Double?): ManualExposureSettings? {
        if (exposureMode != ExposureMode.AUTO) {
            return null
        }
        val currentIso = captureExif.iso ?: return manualExposureSettings
        val currentExposureNs = captureExif.exposureTimeSeconds
            ?.let { (it * 1_000_000_000.0).roundToLong() }
            ?: return manualExposureSettings
        if (currentExposureNs <= 0L) {
            return manualExposureSettings
        }
        val characteristics = backCameraCharacteristics()
        val exposureRange = characteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val sensitivityRange = characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val maxFrameDuration = characteristics?.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)

        val minExposureNs = exposureRange?.lower ?: MIN_MANUAL_EXPOSURE_NS
        val maxExposureNs = exposureRange?.let { range ->
            cameraSettings.maxExposureNs(lensMode).coerceIn(range.lower, range.upper)
        } ?: cameraSettings.maxExposureNs(lensMode)
        val minIso = sensitivityRange?.lower ?: MIN_MANUAL_ISO
        val maxIso = sensitivityRange?.upper ?: MAX_MANUAL_ISO
        val isoCap = cameraSettings.lowNoiseIso().coerceIn(minIso, maxIso)
        val brightnessFactor = exposureAdjustmentFactor(imageBrightness)
        val desiredComposite = currentIso.toDouble() * currentExposureNs.toDouble() * brightnessFactor
        val exposureAtIsoCap = (desiredComposite / isoCap.toDouble()).roundToLong()
        val exposureNs: Long
        val iso: Int
        if (exposureAtIsoCap <= maxExposureNs) {
            exposureNs = exposureAtIsoCap.coerceIn(minExposureNs, maxExposureNs)
            iso = if (exposureAtIsoCap < minExposureNs) {
                (desiredComposite / minExposureNs.toDouble()).roundToInt().coerceIn(minIso, isoCap)
            } else {
                isoCap
            }
        } else {
            exposureNs = maxExposureNs
            iso = (desiredComposite / maxExposureNs.toDouble()).roundToInt().coerceIn(isoCap, maxIso)
        }
        val targetFrameDuration = exposureNs + FRAME_DURATION_PADDING_NS
        val frameDurationNs = maxFrameDuration?.let { maxDuration ->
            targetFrameDuration.coerceIn(exposureNs, maxDuration)
        } ?: targetFrameDuration
        return ManualExposureSettings(exposureNs, frameDurationNs, iso)
    }

    private fun exposureAdjustmentFactor(imageBrightness: Double?): Double {
        val brightness = imageBrightness ?: return 1.0
        if (brightness <= 0.0) {
            return MAX_EXPOSURE_ADJUSTMENT_FACTOR
        }
        val rawFactor = TARGET_LUMA / brightness
        if (rawFactor in (1.0 - EXPOSURE_DEADBAND)..(1.0 + EXPOSURE_DEADBAND)) {
            return 1.0
        }
        return rawFactor.coerceIn(MIN_EXPOSURE_ADJUSTMENT_FACTOR, MAX_EXPOSURE_ADJUSTMENT_FACTOR)
    }

    private fun backCameraCharacteristics(): CameraCharacteristics? {
        return try {
            val cameraManager = getSystemService(CameraManager::class.java)
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return null
            cameraManager.getCameraCharacteristics(cameraId)
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to read camera characteristics", exception)
            null
        }
    }

    private fun averageNormalizedLuma(photoFile: File): Double? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photoFile.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        val sampleSize = maxOf(1, minOf(bounds.outWidth, bounds.outHeight) / LUMA_SAMPLE_SIZE)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath, options) ?: return null
        var total = 0.0
        val count = bitmap.width * bitmap.height
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                total += (0.2126 * Color.red(pixel) + 0.7152 * Color.green(pixel) + 0.0722 * Color.blue(pixel)) / 255.0
            }
        }
        bitmap.recycle()
        return if (count > 0) total / count else null
    }

    private fun updateNotification(text: String) {
        lastNotification = text
        serviceLastNotification = text
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        lastNotification = text
        serviceLastNotification = text
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Fenetre capture")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "cam.fenetre.android.START_CAPTURE"
        const val ACTION_STOP = "cam.fenetre.android.STOP_CAPTURE"
        const val ACTION_CAPTURE_NOW = "cam.fenetre.android.CAPTURE_NOW"
        const val ACTION_BUILD_DAILY_TIMELAPSE = "cam.fenetre.android.BUILD_DAILY_TIMELAPSE"
        const val ACTION_BUILD_DAYLIGHT = "cam.fenetre.android.BUILD_DAYLIGHT"
        const val ACTION_RUN_STORAGE_MANAGEMENT = "cam.fenetre.android.RUN_STORAGE_MANAGEMENT"
        @Volatile private var serviceRunning = false
        @Volatile private var serviceCaptureInProgress = false
        @Volatile private var serviceLastNotification = "Not started"

        fun runtimeSnapshot(): FenetreServiceSnapshot {
            return FenetreServiceSnapshot(
                running = serviceRunning,
                captureInProgress = serviceCaptureInProgress,
                lastNotification = serviceLastNotification,
            )
        }

        private const val CHANNEL_ID = "fenetre_capture"
        private const val NOTIFICATION_ID = 1001
        private const val FRAME_DURATION_PADDING_NS = 500_000_000L
        private const val MIN_MANUAL_EXPOSURE_NS = 100_000L
        private const val MIN_MANUAL_ISO = 25
        private const val MAX_MANUAL_ISO = 6400
        private const val MIN_CAPTURE_TIMEOUT_MS = 60_000L
        private const val CAPTURE_TIMEOUT_PADDING_MS = 15_000L
        private const val COOLDOWN_CHECK_INTERVAL_MS = 60_000L
        private const val TARGET_LUMA = 0.45
        private const val EXPOSURE_DEADBAND = 0.08
        private const val MIN_EXPOSURE_ADJUSTMENT_FACTOR = 0.8
        private const val MAX_EXPOSURE_ADJUSTMENT_FACTOR = 1.25
        private const val LUMA_SAMPLE_SIZE = 256
        private const val TAG = "FenetreCaptureService"
    }
}

data class ManualExposureSettings(
    val exposureTimeNs: Long,
    val frameDurationNs: Long,
    val iso: Int,
) {
    fun exposureTimeSeconds(): Double = exposureTimeNs / 1_000_000_000.0
}

data class FenetreServiceSnapshot(
    val running: Boolean,
    val captureInProgress: Boolean,
    val lastNotification: String,
)
