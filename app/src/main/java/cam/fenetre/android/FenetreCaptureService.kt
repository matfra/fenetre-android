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
import android.util.Range
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ZoomState
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
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
    private lateinit var vignetteCorrection: FenetreVignetteCorrection
    private lateinit var ssim: FenetreSsim
    private lateinit var sunSchedule: FenetreSunSchedule
    private var extensionsManager: ExtensionsManager? = null
    private var webServer: FenetreWebServer? = null
    private var adminServer: FenetreAdminServer? = null
    private var imageCapture: ImageCapture? = null
    private var lensMode = LensMode.ULTRA_WIDE
    private var rotationDegrees = 90
    private var exposureMode = ExposureMode.AUTO
    private var captureMode = ExposureMode.AUTO
    private var adaptiveCaptureMode = ExposureMode.PHONE_AUTO
    private var selectedCameraId: String? = null
    private var manualExposureSettings: ManualExposureSettings? = null
    private var captureInProgress = false
    private var captureGeneration = 0
    private var captureTimeoutRunnable: Runnable? = null
    private var cooldownRunnable: Runnable? = null
    private var previousSsimSample: SsimSample? = null
    private var dynamicSsimIntervalSeconds = 0
    private var lastSsimValue: Double? = null
    private var lastStarsDetected = false
    private var lastStarCount = 0
    private var lastSsimSuppressedByStars = false
    private var lastStarThresholdLuma: Int? = null
    private var lastStarBackgroundLuma: Int? = null
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
        vignetteCorrection = FenetreVignetteCorrection(cameraSettings)
        ssim = FenetreSsim(cameraSettings)
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
                    manualExposureSettings = manualExposureSettings,
                    activeNightCaptureStrategy = activeNightCaptureStrategy(),
                    cameraXNightExtensionAvailable = cameraXNightExtensionAvailable(),
                    selectedCameraId = selectedCameraId,
                    selectedCameraMaxExposureSeconds = selectedCameraMaxExposureSeconds(),
                    selectedCameraVendorMaxExposureSeconds = selectedCameraVendorMaxExposureSeconds(),
                    ssimValue = lastSsimValue,
                    ssimIntervalSeconds = effectiveCaptureIntervalSeconds(),
                    starsDetected = lastStarsDetected,
                    starCount = lastStarCount,
                    ssimSuppressedByStars = lastSsimSuppressedByStars,
                    starThresholdLuma = lastStarThresholdLuma,
                    starBackgroundLuma = lastStarBackgroundLuma,
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
                ensureExtensionsManager(provider)
                lensMode = cameraSettings.lensMode()
                selectedCameraId = cameraIdForLensMode(lensMode)
                rotationDegrees = cameraSettings.rotationDegrees()
                exposureMode = cameraSettings.exposureMode()
                captureMode = resolvedCaptureMode()
                if (captureMode != ExposureMode.AUTO) {
                    manualExposureSettings = null
                }
                val activeNightStrategy = activeNightCaptureStrategy()
                if (activeNightStrategy != NightCaptureStrategy.MANUAL_ADAPTIVE) {
                    manualExposureSettings = null
                }
                val capture = buildImageCapture(captureMode)

                clearCaptureInProgress()
                provider.unbindAll()
                val cameraSelector = cameraSelectorFor(activeNightStrategy)
                val camera = provider.bindToLifecycle(this, cameraSelector, capture)
                imageCapture = capture
                if (activeNightStrategy != NightCaptureStrategy.CAMERAX_NIGHT_EXTENSION) {
                    val zoomState = camera.cameraInfo.zoomState
                    if (zoomState.value == null) {
                        zoomState.observe(this) { state -> applyLensMode(state, camera.cameraControl) }
                    } else {
                        applyLensMode(zoomState.value, camera.cameraControl)
                    }
                }
                scheduleNextCapture(delayMs = scheduleDelayMs)
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun ensureExtensionsManager(provider: ProcessCameraProvider) {
        if (extensionsManager != null) {
            return
        }
        extensionsManager = try {
            ExtensionsManager.getInstanceAsync(this, provider).get()
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to initialize CameraX extensions", exception)
            null
        }
    }

    private fun buildImageCapture(mode: ExposureMode): ImageCapture {
        val activeNightStrategy = activeNightCaptureStrategy()
        val captureMode = if (activeNightStrategy == NightCaptureStrategy.CAMERAX_NIGHT_EXTENSION) {
            ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        } else {
            ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
        val builder = ImageCapture.Builder()
            .setCaptureMode(captureMode)
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
        applyLensCorrectionOptions(extender)
        applyFocusOptions(extender)
        if (mode == ExposureMode.AUTO && activeNightStrategy == NightCaptureStrategy.CAMERA2_NIGHT_SCENE) {
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_MODE,
                CaptureRequest.CONTROL_MODE_USE_SCENE_MODE
            )
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_SCENE_MODE,
                CaptureRequest.CONTROL_SCENE_MODE_NIGHT
            )
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_CAPTURE_INTENT,
                CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE
            )
            return builder.build()
        }
        if (mode == ExposureMode.AUTO && manualExposureSettings != null) {
            val settings = manualExposureSettings ?: return builder.build()
            applySamsungNightOptions(extender)
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_OFF
            )
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
            extender.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, settings.exposureTimeNs)
            extender.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, settings.frameDurationNs)
            extender.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, settings.iso)
        }
        return builder.build()
    }

    private fun applySamsungNightOptions(extender: Camera2Interop.Extender<ImageCapture>) {
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            return
        }
        extender.setCaptureRequestOption(SAMSUNG_SUPER_NIGHT_SHOT_MODE, 1)
        extender.setCaptureRequestOption(SAMSUNG_SSM_SHOT_MODE, 1)
    }

    private fun applyLensCorrectionOptions(extender: Camera2Interop.Extender<ImageCapture>) {
        extender.setCaptureRequestOption(
            CaptureRequest.SHADING_MODE,
            CaptureRequest.SHADING_MODE_HIGH_QUALITY
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            extender.setCaptureRequestOption(
                CaptureRequest.DISTORTION_CORRECTION_MODE,
                CaptureRequest.DISTORTION_CORRECTION_MODE_FAST
            )
        }
    }

    private fun applyFocusOptions(extender: Camera2Interop.Extender<ImageCapture>) {
        if (cameraSettings.focusInfinityEnabled()) {
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )
            extender.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, INFINITY_FOCUS_DIOPTERS)
        } else {
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        }
    }

    private fun cameraSelectorFor(strategy: NightCaptureStrategy): CameraSelector {
        if (strategy != NightCaptureStrategy.CAMERAX_NIGHT_EXTENSION) {
            val cameraId = selectedCameraId ?: return CameraSelector.DEFAULT_BACK_CAMERA
            return CameraSelector.Builder()
                .addCameraFilter { cameraInfos ->
                    cameraInfos.filter { info ->
                        Camera2CameraInfo.from(info).cameraId == cameraId
                    }
                }
                .build()
        }
        val manager = extensionsManager ?: return CameraSelector.DEFAULT_BACK_CAMERA
        return try {
            manager.getExtensionEnabledCameraSelector(CameraSelector.DEFAULT_BACK_CAMERA, ExtensionMode.NIGHT)
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to enable CameraX night extension", exception)
            CameraSelector.DEFAULT_BACK_CAMERA
        }
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

    private fun scheduleNextCapture(delayMs: Long = effectiveCaptureIntervalSeconds() * 1000L) {
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
        val vignetteCorrectionApplied = captureMode == ExposureMode.AUTO &&
            activeNightCaptureStrategy() == NightCaptureStrategy.MANUAL_ADAPTIVE &&
            vignetteCorrection.apply(photoFile)
        val ssimResult = updateSsim(photoFile)
        overlays.apply(photoFile)
        photoFile.copyTo(storage.latestFile(), overwrite = true)
        daylight.observe(photoFile)
        storage.writeMetadata(
            photoFile,
            lensMode,
            rotationDegrees,
            exposureMode,
            captureMode,
            cameraSettings.nightCaptureStrategy(),
            activeNightCaptureStrategy(),
            cameraSettings.focusInfinityEnabled(),
            manualExposureSettings,
            exposureComposite,
            imageBrightness,
            vignetteCorrectionApplied,
            ssimResult,
            captureExif,
        )
        if (!pauseForCooldownIfNeeded()) {
            timelapse.scheduleFrequent()
            timelapse.scheduleDailyForCompletedDays()
            daylight.scheduleCompletedDays()
            storageManager.maybeSchedule()
        }
        updateNotification("Last capture: ${photoFile.name}; web: ${webServer?.url().orEmpty()}")
        val modeChanged = updateAdaptiveCaptureMode(imageBrightness)
        val nextManualExposure = nextManualExposureSettings(
            captureExif,
            imageBrightness,
            manualModeActive = exposureMode == ExposureMode.AUTO && adaptiveCaptureMode == ExposureMode.AUTO,
        )
        if (modeChanged || nextManualExposure != manualExposureSettings) {
            manualExposureSettings = nextManualExposure
            mainHandler.post { bindCamera(scheduleDelayMs = effectiveCaptureIntervalSeconds() * 1000L) }
        } else {
            scheduleNextCapture()
        }
    }

    private fun updateSsim(photoFile: File): SsimResult {
        if (!cameraSettings.ssimEnabled()) {
            lastSsimValue = null
            clearStarDetectionStatus()
            return ssimResult(null, effectiveCaptureIntervalSeconds(), compared = false)
        }
        val analysis = ssim.analyze(photoFile)
        if (analysis == null) {
            return ssimResult(lastSsimValue, effectiveCaptureIntervalSeconds(), compared = false)
        }
        val currentSample = analysis.sample
        val starDetectionActive = cameraSettings.starDetectionEnabled() && sunSchedule.isNightWindow()
        if (starDetectionActive) {
            updateStarDetectionStatus(analysis.starDetection)
        } else {
            clearStarDetectionStatus()
        }
        if (sunSchedule.isSunriseSunsetWindow()) {
            previousSsimSample = currentSample
            lastSsimValue = null
            lastSsimSuppressedByStars = false
            return ssimResult(null, effectiveCaptureIntervalSeconds(), compared = false)
        }
        if (starDetectionActive && analysis.starDetection.detected) {
            previousSsimSample = currentSample
            lastSsimValue = null
            lastSsimSuppressedByStars = true
            dynamicSsimIntervalSeconds = cameraSettings.starCaptureIntervalSeconds()
            Log.i(
                TAG,
                "Stars detected count=${analysis.starDetection.count}; suppressing SSIM interval ${dynamicSsimIntervalSeconds}s"
            )
            return ssimResult(null, dynamicSsimIntervalSeconds, compared = false)
        }
        lastSsimSuppressedByStars = false
        val previousSample = previousSsimSample
        previousSsimSample = currentSample
        if (previousSample == null) {
            dynamicSsimIntervalSeconds = cameraSettings.captureIntervalSeconds()
                .coerceIn(cameraSettings.ssimMinIntervalSeconds(), cameraSettings.ssimMaxIntervalSeconds())
            lastSsimValue = null
            return ssimResult(null, effectiveCaptureIntervalSeconds(), compared = false)
        }
        val value = ssim.compare(previousSample, currentSample).coerceIn(0.0, 1.0)
        lastSsimValue = value
        val setpoint = cameraSettings.ssimSetpoint()
        val currentInterval = effectiveCaptureIntervalSeconds()
        dynamicSsimIntervalSeconds = if (value < setpoint) {
            (currentInterval * cameraSettings.ssimDecreaseFactor())
                .roundToInt()
                .coerceAtLeast(cameraSettings.ssimMinIntervalSeconds())
        } else {
            (currentInterval + cameraSettings.ssimIncreaseSeconds())
                .coerceAtMost(cameraSettings.ssimMaxIntervalSeconds())
        }
        Log.i(
            TAG,
            "SSIM $value target $setpoint interval ${dynamicSsimIntervalSeconds}s"
        )
        return ssimResult(value, dynamicSsimIntervalSeconds, compared = true)
    }

    private fun effectiveCaptureIntervalSeconds(): Int {
        if (sunSchedule.isSunriseSunsetWindow()) {
            return sunSchedule.captureIntervalSeconds()
        }
        if (!cameraSettings.ssimEnabled()) {
            return cameraSettings.captureIntervalSeconds()
        }
        if (lastSsimSuppressedByStars) {
            return cameraSettings.starCaptureIntervalSeconds()
        }
        if (dynamicSsimIntervalSeconds <= 0) {
            dynamicSsimIntervalSeconds = cameraSettings.captureIntervalSeconds()
                .coerceIn(cameraSettings.ssimMinIntervalSeconds(), cameraSettings.ssimMaxIntervalSeconds())
        }
        return dynamicSsimIntervalSeconds
            .coerceIn(cameraSettings.ssimMinIntervalSeconds(), cameraSettings.ssimMaxIntervalSeconds())
    }

    private fun updateStarDetectionStatus(result: StarDetectionResult) {
        lastStarsDetected = result.detected
        lastStarCount = result.count
        lastStarThresholdLuma = result.thresholdLuma
        lastStarBackgroundLuma = result.backgroundLuma
    }

    private fun clearStarDetectionStatus() {
        lastStarsDetected = false
        lastStarCount = 0
        lastSsimSuppressedByStars = false
        lastStarThresholdLuma = null
        lastStarBackgroundLuma = null
    }

    private fun ssimResult(value: Double?, intervalSeconds: Int, compared: Boolean): SsimResult {
        return SsimResult(
            value = value,
            target = cameraSettings.ssimSetpoint(),
            intervalSeconds = intervalSeconds,
            compared = compared,
            starsDetected = lastStarsDetected,
            starCount = lastStarCount,
            suppressedByStars = lastSsimSuppressedByStars,
            starThresholdLuma = lastStarThresholdLuma,
            starBackgroundLuma = lastStarBackgroundLuma,
        )
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

    private fun resolvedCaptureMode(): ExposureMode {
        if (exposureMode == ExposureMode.AUTO) {
            return adaptiveCaptureMode
        }
        return exposureMode
    }

    private fun updateAdaptiveCaptureMode(imageBrightness: Double?): Boolean {
        if (exposureMode != ExposureMode.AUTO) {
            return setAdaptiveCaptureMode(ExposureMode.PHONE_AUTO)
        }
        val luma = imageBrightness ?: return false
        val manualTarget = cameraSettings.manualNightTargetLuma()
        val nextMode = if (adaptiveCaptureMode == ExposureMode.PHONE_AUTO) {
            if (luma <= manualTarget) {
                Log.i(
                    TAG,
                    "Switching to night capture strategy: luma $luma <= target $manualTarget"
                )
                ExposureMode.AUTO
            } else {
                ExposureMode.PHONE_AUTO
            }
        } else if (luma >= manualTarget + cameraSettings.manualToAutoLumaMargin()) {
            Log.i(
                TAG,
                "Switching to phone auto exposure: luma $luma >= target $manualTarget + " +
                    "margin ${cameraSettings.manualToAutoLumaMargin()}"
            )
            ExposureMode.PHONE_AUTO
        } else {
            ExposureMode.AUTO
        }
        return setAdaptiveCaptureMode(nextMode)
    }

    private fun setAdaptiveCaptureMode(mode: ExposureMode): Boolean {
        if (adaptiveCaptureMode == mode) {
            return false
        }
        adaptiveCaptureMode = mode
        if (mode != ExposureMode.AUTO) {
            manualExposureSettings = null
        }
        return true
    }

    private fun nextManualExposureSettings(
        captureExif: CaptureExif,
        imageBrightness: Double?,
        manualModeActive: Boolean = captureMode == ExposureMode.AUTO,
    ): ManualExposureSettings? {
        if (!manualModeActive) {
            return null
        }
        if (activeNightCaptureStrategy() != NightCaptureStrategy.MANUAL_ADAPTIVE) {
            return null
        }
        val requestedSettings = manualExposureSettings
        val currentIso = requestedSettings?.iso ?: captureExif.iso ?: return manualExposureSettings
        val currentExposureNs = requestedSettings?.exposureTimeNs ?: captureExif.exposureTimeSeconds
            ?.let { (it * 1_000_000_000.0).roundToLong() }
            ?: return manualExposureSettings
        if (currentExposureNs <= 0L) {
            return manualExposureSettings
        }
        val characteristics = backCameraCharacteristics()
        val exposureRange = characteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val sensitivityRange = characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val maxFrameDuration = characteristics?.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)

        val vendorExposureRange = samsungVendorExposureTimeRange(characteristics)
        val effectiveExposureRange = vendorExposureRange ?: exposureRange
        val minExposureNs = effectiveExposureRange?.lower ?: MIN_MANUAL_EXPOSURE_NS
        val configuredMaxExposureNs = if (vendorExposureRange != null) {
            minOf(cameraSettings.maxExposureNs(lensMode), SAMSUNG_SAFE_VENDOR_EXPOSURE_NS)
        } else {
            cameraSettings.maxExposureNs(lensMode)
        }
        val maxExposureNs = effectiveExposureRange?.let { range ->
            configuredMaxExposureNs.coerceIn(range.lower, range.upper)
        } ?: configuredMaxExposureNs
        val minIso = sensitivityRange?.lower ?: MIN_MANUAL_ISO
        val maxIso = sensitivityRange?.upper ?: MAX_MANUAL_ISO
        val isoCap = cameraSettings.lowNoiseIso().coerceIn(minIso, maxIso)
        val brightnessFactor = exposureAdjustmentFactor(imageBrightness)
        val desiredComposite = currentIso.toDouble() *
            currentExposureNs.toDouble() *
            brightnessFactor
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
        val frameDurationNs = maxFrameDuration?.takeIf { maxDuration ->
            vendorExposureRange == null || maxDuration >= exposureNs
        }?.let { maxDuration ->
            targetFrameDuration.coerceIn(exposureNs, maxDuration)
        } ?: targetFrameDuration
        return ManualExposureSettings(exposureNs, frameDurationNs, iso)
    }

    private fun exposureAdjustmentFactor(imageBrightness: Double?): Double {
        val brightness = imageBrightness ?: return 1.0
        if (brightness <= 0.0) {
            return MAX_BRIGHTEN_EXPOSURE_ADJUSTMENT_FACTOR
        }
        val rawFactor = cameraSettings.manualNightTargetLuma() / brightness
        if (rawFactor in (1.0 - EXPOSURE_DEADBAND)..(1.0 + EXPOSURE_DEADBAND)) {
            return 1.0
        }
        return rawFactor.coerceIn(MIN_DARKEN_EXPOSURE_ADJUSTMENT_FACTOR, MAX_BRIGHTEN_EXPOSURE_ADJUSTMENT_FACTOR)
    }

    private fun activeNightCaptureStrategy(): NightCaptureStrategy {
        if (captureMode != ExposureMode.AUTO) {
            return NightCaptureStrategy.MANUAL_ADAPTIVE
        }
        val strategy = cameraSettings.nightCaptureStrategy()
        if (strategy == NightCaptureStrategy.CAMERA2_NIGHT_SCENE && !camera2NightSceneAvailable()) {
            return NightCaptureStrategy.MANUAL_ADAPTIVE
        }
        if (strategy == NightCaptureStrategy.CAMERAX_NIGHT_EXTENSION && !cameraXNightExtensionAvailable()) {
            return NightCaptureStrategy.MANUAL_ADAPTIVE
        }
        return strategy
    }

    private fun camera2NightSceneAvailable(): Boolean {
        val modes = backCameraCharacteristics()
            ?.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
            ?: return false
        return modes.contains(CaptureRequest.CONTROL_SCENE_MODE_NIGHT)
    }

    private fun cameraXNightExtensionAvailable(): Boolean {
        val manager = extensionsManager ?: return false
        return try {
            manager.isExtensionAvailable(CameraSelector.DEFAULT_BACK_CAMERA, ExtensionMode.NIGHT)
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to check CameraX night extension", exception)
            false
        }
    }

    private fun backCameraCharacteristics(): CameraCharacteristics? {
        return try {
            val cameraManager = getSystemService(CameraManager::class.java)
            val cameraId = selectedCameraId ?: cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return null
            cameraManager.getCameraCharacteristics(cameraId)
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to read camera characteristics", exception)
            null
        }
    }

    private fun selectedCameraMaxExposureSeconds(): Double? {
        return backCameraCharacteristics()
            ?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?.upper
            ?.let { it / 1_000_000_000.0 }
    }

    private fun selectedCameraVendorMaxExposureSeconds(): Double? {
        return samsungVendorExposureTimeRange(backCameraCharacteristics())
            ?.upper
            ?.let { it / 1_000_000_000.0 }
    }

    private fun samsungVendorExposureTimeRange(
        characteristics: CameraCharacteristics?,
    ): Range<Long>? {
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            return null
        }
        return try {
            val values = characteristics?.get(SAMSUNG_EXPOSURE_TIME_RANGE) ?: return null
            if (values.size < 2) {
                null
            } else {
                val lower = values[0]
                val upper = values[1]
                if (lower > 0L && upper >= lower) Range(lower, upper) else null
            }
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to read Samsung vendor exposure range", exception)
            null
        }
    }

    private fun cameraIdForLensMode(mode: LensMode): String? {
        return try {
            val cameraManager = getSystemService(CameraManager::class.java)
            val backCameras = cameraManager.cameraIdList.mapNotNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                if (characteristics.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
                    return@mapNotNull null
                }
                val focalLength = characteristics
                    .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull()
                    ?: return@mapNotNull null
                id to focalLength
            }.sortedBy { it.second }

            when (mode) {
                LensMode.ULTRA_WIDE -> backCameras.firstOrNull()?.first
                LensMode.WIDE -> backCameras.getOrNull(backCameras.size / 2)?.first
                LensMode.TELE -> backCameras.lastOrNull()?.first
            }
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to select camera ID for lens mode", exception)
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
        private const val SAMSUNG_SAFE_VENDOR_EXPOSURE_NS = 8_000_000_000L
        private const val MIN_MANUAL_EXPOSURE_NS = 100_000L
        private const val MIN_MANUAL_ISO = 25
        private const val MAX_MANUAL_ISO = 6400
        private const val MIN_CAPTURE_TIMEOUT_MS = 60_000L
        private const val CAPTURE_TIMEOUT_PADDING_MS = 15_000L
        private const val COOLDOWN_CHECK_INTERVAL_MS = 60_000L
        private const val INFINITY_FOCUS_DIOPTERS = 0.0f
        private const val EXPOSURE_DEADBAND = 0.08
        private const val MIN_DARKEN_EXPOSURE_ADJUSTMENT_FACTOR = 0.25
        private const val MAX_BRIGHTEN_EXPOSURE_ADJUSTMENT_FACTOR = 2.0
        private const val LUMA_SAMPLE_SIZE = 256
        private const val TAG = "FenetreCaptureService"
        private val SAMSUNG_SUPER_NIGHT_SHOT_MODE = CaptureRequest.Key(
            "samsung.android.control.superNightShotMode",
            Int::class.javaObjectType,
        )
        private val SAMSUNG_SSM_SHOT_MODE = CaptureRequest.Key(
            "samsung.android.control.ssmShotMode",
            Int::class.javaObjectType,
        )
        private val SAMSUNG_EXPOSURE_TIME_RANGE = CameraCharacteristics.Key(
            "samsung.android.sensor.info.exposureTimeRange",
            LongArray::class.java,
        )
    }
}

data class ManualExposureSettings(
    val exposureTimeNs: Long,
    val frameDurationNs: Long,
    val iso: Int,
) {
    fun exposureTimeSeconds(): Double = exposureTimeNs / 1_000_000_000.0
    fun frameDurationSeconds(): Double = frameDurationNs / 1_000_000_000.0
}

data class FenetreServiceSnapshot(
    val running: Boolean,
    val captureInProgress: Boolean,
    val lastNotification: String,
)
