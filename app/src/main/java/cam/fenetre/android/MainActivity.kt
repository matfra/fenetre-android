package cam.fenetre.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var statusText: TextView
    private lateinit var serviceStateText: TextView
    private lateinit var captureStateText: TextView
    private lateinit var latestCaptureAgeText: TextView
    private lateinit var thermalStateText: TextView
    private lateinit var storageStateText: TextView
    private lateinit var notificationStateText: TextView
    private lateinit var storage: FenetreStorage
    private lateinit var cameraSettings: FenetreCameraSettings
    private lateinit var latitudeInput: EditText
    private lateinit var longitudeInput: EditText
    private val stateHandler = Handler(Looper.getMainLooper())
    private val stateRefreshRunnable = object : Runnable {
        override fun run() {
            updateStatePanel()
            stateHandler.postDelayed(this, STATE_REFRESH_INTERVAL_MS)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updateStatus("Permissions updated")
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            useDeviceLocation()
        } else {
            updateStatus("Location permission denied; coordinates unchanged")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraSettings = FenetreCameraSettings(this)
        FenetreBundledFfmpeg.installIfAvailable(this)
        storage = FenetreStorage(this, cameraSettings)
        setContentView(buildContentView())
        requestNeededPermissions()
        updateStatus(statusSummary())
    }

    override fun onResume() {
        super.onResume()
        updateStatePanel()
        stateHandler.removeCallbacks(stateRefreshRunnable)
        stateHandler.postDelayed(stateRefreshRunnable, STATE_REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        stateHandler.removeCallbacks(stateRefreshRunnable)
        super.onPause()
    }

    private fun buildContentView(): View {
        statusText = TextView(this).apply {
            textSize = 15f
            setTextColor(0xff45505f.toInt())
            setPadding(0, 8, 0, 0)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 44, 36, 44)
        }

        content.addView(TextView(this).apply {
            text = cameraSettings.deploymentName()
            textSize = 30f
            setTextColor(0xff111827.toInt())
        })
        content.addView(TextView(this).apply {
            text = "Phone camera settings"
            textSize = 16f
            setTextColor(0xff5f6b7a.toInt())
            setPadding(0, 4, 0, 24)
        })
        content.addView(statePanel())

        content.addView(sectionTitle("Capture"))
        content.addView(actionGrid())

        content.addView(sectionTitle("Lens"))
        content.addView(lensGroup())
        content.addView(settingCheckBox("Lock focus to infinity", cameraSettings.focusInfinityEnabled()) {
            cameraSettings.setFocusInfinityEnabled(it)
        })

        content.addView(sectionTitle("Exposure"))
        content.addView(exposureGroup())
        content.addView(helpText("Adaptive low ISO caps ISO until the configured max exposure is reached, then allows ISO to rise. Phone auto leaves exposure to Android."))

        content.addView(sectionTitle("Rotation"))
        content.addView(rotationGroup())

        content.addView(sectionTitle("Deployment"))
        content.addView(settingEditText("Camera name", cameraSettings.cameraName()) {
            cameraSettings.setCameraName(it)
        })
        content.addView(settingEditText("Deployment name", cameraSettings.deploymentName()) {
            cameraSettings.setDeploymentName(it)
        })
        content.addView(settingEditText("Public URL", cameraSettings.publicBaseUrl()) {
            cameraSettings.setPublicBaseUrl(it)
        })
        content.addView(settingEditText("Description", cameraSettings.cameraDescription()) {
            cameraSettings.setCameraDescription(it)
        })
        content.addView(settingEditText("Comparison URL", cameraSettings.comparisonUrl()) {
            cameraSettings.setComparisonUrl(it)
        })
        content.addView(settingCheckBox("Show fenetre.cam link", cameraSettings.canonicalWebsiteLinkEnabled()) {
            cameraSettings.setCanonicalWebsiteLinkEnabled(it)
        })

        content.addView(sectionTitle("Web server"))
        content.addView(settingEditText("Local host", cameraSettings.webHost()) {
            cameraSettings.setWebHost(it)
        })
        content.addView(settingEditText("Port", cameraSettings.webPort().toString(), InputType.TYPE_CLASS_NUMBER) {
            it.toIntOrNull()?.let(cameraSettings::setWebPort)
        })
        content.addView(settingEditText("Admin port", cameraSettings.adminPort().toString(), InputType.TYPE_CLASS_NUMBER) {
            it.toIntOrNull()?.let(cameraSettings::setAdminPort)
        })
        content.addView(helpText("Changing ports takes effect after Apply & restart. The admin port is read-only and intended for LAN access only."))

        content.addView(sectionTitle("Timing"))
        content.addView(settingEditText("Capture interval seconds", cameraSettings.captureIntervalSeconds().toString(), InputType.TYPE_CLASS_NUMBER) {
            it.toIntOrNull()?.let(cameraSettings::setCaptureIntervalSeconds)
        })
        content.addView(sectionTitle("Daily timelapse"))
        content.addView(dailyTimelapseEncoderGroup())
        content.addView(settingEditText(
            "VP9 bitrate Mbps",
            cameraSettings.dailyVp9BitrateMbps().toString(),
            decimalInputType(),
        ) {
            it.toDoubleOrNull()?.let(cameraSettings::setDailyVp9BitrateMbps)
        })
        content.addView(settingEditText("FFmpeg executable path", cameraSettings.ffmpegExecutablePath()) {
            cameraSettings.setFfmpegExecutablePath(it)
        })
        content.addView(helpText("H.264 fast uses Android MediaCodec and stays the default. VP9 high quality writes WebM and requires an FFmpeg executable with libvpx-vp9 support."))
        content.addView(sectionTitle("Thermal"))
        content.addView(settingCheckBox("Thermal cooldown protection", cameraSettings.cooldownEnabled()) {
            cameraSettings.setCooldownEnabled(it)
        })
        content.addView(settingEditText(
            "Cooldown battery temperature C",
            cameraSettings.cooldownBatteryTemperatureCelsius().toString(),
            decimalInputType(),
        ) {
            it.toDoubleOrNull()?.let(cameraSettings::setCooldownBatteryTemperatureCelsius)
        })
        content.addView(thermalStatusThresholdSpinner())
        content.addView(helpText("When enabled, capture and timelapse work pause while the battery is at or above this temperature. Pick a thermal state to also pause when Android reports that state or higher."))

        content.addView(sectionTitle("Storage management"))
        content.addView(settingCheckBox("Storage management", cameraSettings.storageManagementEnabled()) {
            cameraSettings.setStorageManagementEnabled(it)
        })
        content.addView(settingCheckBox("Storage dry run", cameraSettings.storageManagementDryRun()) {
            cameraSettings.setStorageManagementDryRun(it)
        })
        content.addView(settingEditText("Storage check interval seconds", cameraSettings.storageManagementCheckIntervalSeconds().toString(), InputType.TYPE_CLASS_NUMBER) {
            it.toIntOrNull()?.let(cameraSettings::setStorageManagementCheckIntervalSeconds)
        })
        content.addView(settingEditText("Storage max GB", cameraSettings.storageManagementMaxSizeGb().toString(), InputType.TYPE_CLASS_NUMBER) {
            it.toIntOrNull()?.let(cameraSettings::setStorageManagementMaxSizeGb)
        })
        content.addView(settingCheckBox("Archive completed days", cameraSettings.storageArchiveEnabled()) {
            cameraSettings.setStorageArchiveEnabled(it)
        })
        content.addView(settingEditText("Archive after days", cameraSettings.storageArchiveAfterDays().toString(), InputType.TYPE_CLASS_NUMBER) {
            it.toIntOrNull()?.let(cameraSettings::setStorageArchiveAfterDays)
        })
        content.addView(settingEditText("Archive JPEGs to keep", cameraSettings.storageArchiveFilesToKeep().toString(), InputType.TYPE_CLASS_NUMBER) {
            it.toIntOrNull()?.let(cameraSettings::setStorageArchiveFilesToKeep)
        })
        content.addView(actionButton("Run storage management") {
            sendServiceAction(FenetreCaptureService.ACTION_RUN_STORAGE_MANAGEMENT)
            updateStatus("Storage management requested")
        })
        content.addView(helpText("Archiving keeps a representative subset of JPEGs after daylight and daily timelapse files exist. Size cleanup deletes oldest day directories when the app data directory is over the configured limit."))

        content.addView(settingCheckBox("Fast capture near sunrise and sunset", cameraSettings.sunriseSunsetFastEnabled()) {
            cameraSettings.setSunriseSunsetFastEnabled(it)
        })
        content.addView(settingEditText("Sunrise/sunset interval seconds", cameraSettings.sunriseSunsetFastIntervalSeconds().toString(), InputType.TYPE_CLASS_NUMBER) {
            it.toIntOrNull()?.let(cameraSettings::setSunriseSunsetFastIntervalSeconds)
        })
        content.addView(settingEditText("Sunrise start minutes before", cameraSettings.sunriseOffsetStartMinutes().toString(), InputType.TYPE_CLASS_NUMBER) {
            it.toIntOrNull()?.let(cameraSettings::setSunriseOffsetStartMinutes)
        })
        content.addView(settingEditText("Sunrise end minutes after", cameraSettings.sunriseOffsetEndMinutes().toString(), InputType.TYPE_CLASS_NUMBER) {
            it.toIntOrNull()?.let(cameraSettings::setSunriseOffsetEndMinutes)
        })
        content.addView(settingEditText("Sunset start minutes before", cameraSettings.sunsetOffsetStartMinutes().toString(), InputType.TYPE_CLASS_NUMBER) {
            it.toIntOrNull()?.let(cameraSettings::setSunsetOffsetStartMinutes)
        })
        content.addView(settingEditText("Sunset end minutes after", cameraSettings.sunsetOffsetEndMinutes().toString(), InputType.TYPE_CLASS_NUMBER) {
            it.toIntOrNull()?.let(cameraSettings::setSunsetOffsetEndMinutes)
        })
        content.addView(helpText("Fast capture uses the overlay timezone, latitude, and longitude. Defaults match the Python service windows."))
        content.addView(nightCaptureStrategySpinner())
        content.addView(settingEditText(
            "Night exposure boost stops",
            cameraSettings.nightExposureBoostStops().toString(),
            decimalInputType(),
        ) {
            it.toDoubleOrNull()?.let(cameraSettings::setNightExposureBoostStops)
        })
        content.addView(settingEditText("Night boost twilight buffer minutes", cameraSettings.nightExposureBoostTwilightBufferMinutes().toString(), InputType.TYPE_CLASS_NUMBER) {
            it.toIntOrNull()?.let(cameraSettings::setNightExposureBoostTwilightBufferMinutes)
        })
        content.addView(helpText("Night boost applies only after sunset plus this buffer and before sunrise minus this buffer. Leave it at 0 to use the normal adaptive brightness target."))
        content.addView(settingEditText("Day composite threshold", cameraSettings.dayExposureCompositeThreshold().toString(), decimalInputType()) {
            it.toDoubleOrNull()?.let(cameraSettings::setDayExposureCompositeThreshold)
        })
        content.addView(settingEditText("Night composite threshold", cameraSettings.nightExposureCompositeThreshold().toString(), decimalInputType()) {
            it.toDoubleOrNull()?.let(cameraSettings::setNightExposureCompositeThreshold)
        })
        content.addView(helpText("Exposure composite is ISO times shutter seconds. Phone auto switches to manual adaptive above the night threshold when ISO rises over the cap; manual adaptive switches back below the day threshold."))
        content.addView(settingEditText("Manual night target brightness", cameraSettings.manualNightTargetLuma().toString(), decimalInputType()) {
            it.toDoubleOrNull()?.let(cameraSettings::setManualNightTargetLuma)
        })
        content.addView(helpText("Manual adaptive exposure targets this average luma. Use values like 0.08 for darker skies or 0.12 for brighter night detail."))
        content.addView(settingCheckBox("Vignette correction", cameraSettings.vignetteCorrectionEnabled()) {
            cameraSettings.setVignetteCorrectionEnabled(it)
        })
        content.addView(settingEditText("Vignette correction strength", cameraSettings.vignetteCorrectionStrength().toString(), decimalInputType()) {
            it.toDoubleOrNull()?.let(cameraSettings::setVignetteCorrectionStrength)
        })
        content.addView(settingEditText("Vignette correction power", cameraSettings.vignetteCorrectionPower().toString(), decimalInputType()) {
            it.toDoubleOrNull()?.let(cameraSettings::setVignetteCorrectionPower)
        })
        content.addView(settingEditText("Vignette correction radius", cameraSettings.vignetteCorrectionRadius().toString(), decimalInputType()) {
            it.toDoubleOrNull()?.let(cameraSettings::setVignetteCorrectionRadius)
        })
        content.addView(helpText("Vignette correction applies a radial post-process attenuation before overlays. Strength controls center darkening; power controls the ramp; radius is where attenuation reaches zero."))
        content.addView(settingEditText("ISO cap", cameraSettings.lowNoiseIso().toString(), InputType.TYPE_CLASS_NUMBER) {
            it.toIntOrNull()?.let(cameraSettings::setLowNoiseIso)
        })
        LensMode.entries.forEach { mode ->
            content.addView(settingEditText("${mode.label} max exposure seconds", cameraSettings.maxExposureSeconds(mode).toString(), decimalInputType()) {
                it.toDoubleOrNull()?.let { seconds -> cameraSettings.setMaxExposureSeconds(mode, seconds) }
            })
        }

        content.addView(sectionTitle("Overlays"))
        content.addView(settingCheckBox("Timestamp overlay", cameraSettings.timestampOverlayEnabled()) {
            cameraSettings.setTimestampOverlayEnabled(it)
        })
        content.addView(settingCheckBox("Sun path overlay", cameraSettings.sunPathOverlayEnabled()) {
            cameraSettings.setSunPathOverlayEnabled(it)
        })
        content.addView(settingEditText("Overlay timezone", cameraSettings.overlayTimezone()) {
            cameraSettings.setOverlayTimezone(it)
        })
        content.addView(settingEditText(
            "Latitude",
            cameraSettings.overlayLatitude().toString(),
            signedDecimalInputType(),
            onInputCreated = { latitudeInput = it },
        ) {
            it.toDoubleOrNull()?.let(cameraSettings::setOverlayLatitude)
        })
        content.addView(settingEditText(
            "Longitude",
            cameraSettings.overlayLongitude().toString(),
            signedDecimalInputType(),
            onInputCreated = { longitudeInput = it },
        ) {
            it.toDoubleOrNull()?.let(cameraSettings::setOverlayLongitude)
        })
        content.addView(actionButton("Use phone location") {
            useDeviceLocation()
        })
        content.addView(helpText("Timestamp is drawn at bottom right. Sun path and sunrise/sunset scheduling use the configured camera location."))

        content.addView(sectionTitle("Status"))
        content.addView(statusText)
        content.addView(helpText("Settings are saved immediately. Use Apply & restart to rebind the camera with the new settings."))

        return ScrollView(this).apply {
            setBackgroundColor(0xfff6f8fb.toInt())
            addView(content)
        }
    }

    private fun actionGrid(): LinearLayout {
        val firstRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        firstRow.addView(actionButton("Start") {
            requestNeededPermissions()
            sendServiceAction(FenetreCaptureService.ACTION_START)
            updateStatus("Capture service starting; web: ${cameraSettings.localWebUrl()}")
        })
        firstRow.addView(actionButton("Capture now") {
            sendServiceAction(FenetreCaptureService.ACTION_CAPTURE_NOW)
            updateStatus("Capture requested")
        })

        val secondRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        secondRow.addView(actionButton("Apply & restart") {
            restartCaptureService()
            updateStatus("Capture service restarted with ${settingsSummary()}")
        })
        secondRow.addView(actionButton("Open web UI") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cameraSettings.localWebUrl())))
        })

        val thirdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        thirdRow.addView(actionButton("Build daily") {
            sendServiceAction(FenetreCaptureService.ACTION_BUILD_DAILY_TIMELAPSE)
            updateStatus("Daily timelapse build queued")
        })
        thirdRow.addView(actionButton("Build daylight") {
            sendServiceAction(FenetreCaptureService.ACTION_BUILD_DAYLIGHT)
            updateStatus("Daylight build queued")
        })
        thirdRow.addView(actionButton("Stop") {
            sendServiceAction(FenetreCaptureService.ACTION_STOP)
            updateStatus("Capture service stopping")
        })

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(firstRow)
            addView(secondRow)
            addView(thirdRow)
        }
    }

    private fun lensGroup(): RadioGroup {
        return RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            LensMode.entries.forEach { mode ->
                addView(radioButton(mode.label, mode == cameraSettings.lensMode()) {
                    cameraSettings.setLensMode(mode)
                    updateStatus("${settingsSummary()}; apply restart when ready")
                })
            }
        }
    }

    private fun exposureGroup(): RadioGroup {
        return RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            ExposureMode.entries.forEach { mode ->
                addView(radioButton(mode.label, mode == cameraSettings.exposureMode()) {
                    cameraSettings.setExposureMode(mode)
                    updateStatus("${settingsSummary()}; apply restart when ready")
                })
            }
        }
    }

    private fun rotationGroup(): RadioGroup {
        return RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            listOf(0, 90, 180, 270).forEach { degrees ->
                addView(radioButton("$degrees", degrees == cameraSettings.rotationDegrees()) {
                    cameraSettings.setRotationDegrees(degrees)
                    updateStatus("${settingsSummary()}; apply restart when ready")
                })
            }
        }
    }

    private fun dailyTimelapseEncoderGroup(): RadioGroup {
        return RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            DailyTimelapseEncoderMode.entries.forEach { mode ->
                addView(radioButton(mode.label, mode == cameraSettings.dailyTimelapseEncoderMode()) {
                    cameraSettings.setDailyTimelapseEncoderMode(mode)
                    updateStatus("${settingsSummary()}; saved daily encoder")
                })
            }
        }
    }

    private fun statePanel(): LinearLayout {
        serviceStateText = stateValueText()
        captureStateText = stateValueText()
        latestCaptureAgeText = stateValueText()
        thermalStateText = stateValueText()
        storageStateText = stateValueText()
        notificationStateText = stateValueText()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 16)
            setBackgroundColor(0xffffffff.toInt())
            addView(TextView(this@MainActivity).apply {
                text = "App state"
                textSize = 18f
                setTextColor(0xff111827.toInt())
                setPadding(0, 0, 0, 8)
            })
            addView(stateRow("Service", serviceStateText))
            addView(stateRow("Capture", captureStateText))
            addView(stateRow("Latest capture", latestCaptureAgeText))
            addView(stateRow("Thermal", thermalStateText))
            addView(stateRow("Storage", storageStateText))
            addView(stateRow("Last update", notificationStateText))
        }
    }

    private fun stateRow(label: String, value: TextView): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 4)
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 14f
                setTextColor(0xff6b7280.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.75f)
            })
            addView(value)
        }
    }

    private fun stateValueText(): TextView {
        return TextView(this).apply {
            textSize = 15f
            setTextColor(0xff111827.toInt())
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
    }

    private fun updateStatePanel() {
        if (!::serviceStateText.isInitialized) {
            return
        }
        val snapshot = FenetreCaptureService.runtimeSnapshot()
        val thermal = FenetreThermal.status(this, cameraSettings)
        val battery = FenetreThermal.batteryStatus(this)
        serviceStateText.text = if (snapshot.running) "Running" else "Stopped"
        captureStateText.text = when {
            thermal.paused -> "Paused for cooldown"
            snapshot.captureInProgress -> "In progress"
            snapshot.running -> "Idle"
            else -> "Not started"
        }
        latestCaptureAgeText.text = latestCaptureAgeTextValue()
        thermalStateText.text = thermalStateTextValue(thermal, battery.second)
        storageStateText.text = storageStateTextValue()
        notificationStateText.text = snapshot.lastNotification
    }

    private fun latestCaptureAgeTextValue(): String {
        val metadata = storage.metadataFile()
        val capturedAtMs = if (metadata.exists()) {
            Regex(""""captured_at_ms"\s*:\s*(\d+)""")
                .find(metadata.readText())
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
        } else {
            null
        }
        return capturedAtMs?.let { "${formatDurationSeconds(maxOf(0L, (System.currentTimeMillis() - it) / 1000L))} ago" }
            ?: "No capture yet"
    }

    private fun thermalStateTextValue(thermal: FenetreThermalStatus, batteryTemperatureCelsius: Double?): String {
        val temperature = batteryTemperatureCelsius?.let { String.format(Locale.US, "%.1fC", it) } ?: "temp n/a"
        val androidStatus = thermal.androidThermalStatus?.toString() ?: "n/a"
        val threshold = if (thermal.thermalStatusThreshold > 0) {
            "state >= ${thermal.thermalStatusThreshold}"
        } else {
            "manual"
        }
        return "$temperature, Android $androidStatus, $threshold"
    }

    private fun storageStateTextValue(): String {
        val root = storage.rootDir()
        val usedBytes = root.totalSpace - root.freeSpace
        return "${formatBytes(usedBytes)} used, ${formatBytes(root.freeSpace)} free"
    }

    private fun formatDurationSeconds(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    private fun formatBytes(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        return String.format(Locale.US, "%.1f GB", gb)
    }

    private fun sectionTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(0xff1f2937.toInt())
            setPadding(0, 26, 0, 8)
        }
    }

    private fun helpText(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 13f
            setTextColor(0xff6b7280.toInt())
            setPadding(0, 4, 0, 0)
        }
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            minHeight = 48
            setAllCaps(false)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 10, 10)
            }
        }
    }

    private fun radioButton(label: String, checked: Boolean, onClick: () -> Unit): RadioButton {
        return RadioButton(this).apply {
            text = label
            textSize = 16f
            isChecked = checked
            setPadding(0, 4, 0, 4)
            setOnClickListener { onClick() }
        }
    }

    private fun settingEditText(
        label: String,
        value: String,
        inputTypeValue: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL,
        onInputCreated: (EditText) -> Unit = {},
        onSave: (String) -> Unit,
    ): LinearLayout {
        val input = EditText(this).apply {
            setText(value)
            textSize = 15f
            inputType = inputTypeValue
            isSingleLine = true
            setSelectAllOnFocus(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        onInputCreated(input)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 4)
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 15f
                setTextColor(0xff374151.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.75f)
            })
            addView(input)
            addView(Button(this@MainActivity).apply {
                text = "Save"
                minHeight = 44
                setAllCaps(false)
                setOnClickListener {
                    onSave(input.text.toString())
                    input.clearFocus()
                    updateStatus("${settingsSummary()}; saved $label")
                }
            })
        }
    }

    private fun settingCheckBox(label: String, checked: Boolean, onSave: (Boolean) -> Unit): CheckBox {
        return CheckBox(this).apply {
            text = label
            textSize = 16f
            isChecked = checked
            setPadding(0, 4, 0, 4)
            setOnCheckedChangeListener { _, value ->
                onSave(value)
                updateStatus("${settingsSummary()}; saved $label")
            }
        }
    }

    private fun nightCaptureStrategySpinner(): LinearLayout {
        val values = NightCaptureStrategy.entries
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                values.map { it.label },
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(values.indexOf(cameraSettings.nightCaptureStrategy()).coerceAtLeast(0), false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    cameraSettings.setNightCaptureStrategy(values[position])
                    updateStatus("${settingsSummary()}; saved Night capture strategy")
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 4)
            addView(TextView(this@MainActivity).apply {
                text = "Night capture strategy"
                textSize = 15f
                setTextColor(0xff374151.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.75f)
            })
            addView(spinner)
        }
    }

    private fun thermalStatusThresholdSpinner(): LinearLayout {
        val values = ThermalStatusThreshold.entries
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                values.map { it.label },
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(values.indexOf(cameraSettings.cooldownThermalStatusThreshold()).coerceAtLeast(0), false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    cameraSettings.setCooldownThermalStatusThreshold(values[position])
                    updateStatus("${settingsSummary()}; saved Thermal state threshold")
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 4)
            addView(TextView(this@MainActivity).apply {
                text = "Thermal state threshold"
                textSize = 15f
                setTextColor(0xff374151.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.75f)
            })
            addView(spinner)
        }
    }

    private fun decimalInputType(): Int {
        return InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    private fun signedDecimalInputType(): Int {
        return InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
    }

    private fun restartCaptureService() {
        sendServiceAction(FenetreCaptureService.ACTION_STOP)
        statusText.postDelayed({
            requestNeededPermissions()
            sendServiceAction(FenetreCaptureService.ACTION_START)
        }, SERVICE_RESTART_DELAY_MS)
    }

    private fun statusSummary(): String {
        return "${settingsSummary()}; output: ${storage.rootPath()}; web: ${cameraSettings.localWebUrl()}"
    }

    private fun settingsSummary(): String {
        val sunriseSunset = if (cameraSettings.sunriseSunsetFastEnabled()) {
            "; sun ${cameraSettings.sunriseSunsetFastIntervalSeconds()}s"
        } else {
            ""
        }
        val cooldown = if (cameraSettings.cooldownEnabled()) {
            "; cooldown ${cameraSettings.cooldownBatteryTemperatureCelsius()}C/${cameraSettings.cooldownThermalStatusThreshold().label}"
        } else {
            ""
        }
        val storageManagement = if (cameraSettings.storageManagementEnabled()) {
            "; storage ${cameraSettings.storageManagementMaxSizeGb()}GB"
        } else {
            ""
        }
        val focus = if (cameraSettings.focusInfinityEnabled()) {
            "; focus infinity"
        } else {
            ""
        }
        val vignette = if (cameraSettings.vignetteCorrectionEnabled()) {
            "; vignette ${cameraSettings.vignetteCorrectionStrength()}"
        } else {
            ""
        }
        return "Camera ${cameraSettings.cameraName()}; lens ${cameraSettings.lensMode().label}$focus; exposure ${cameraSettings.exposureMode().label}; rotate ${cameraSettings.rotationDegrees()}; every ${cameraSettings.captureIntervalSeconds()}s; daily ${cameraSettings.dailyTimelapseEncoderMode().label}; night ${cameraSettings.nightCaptureStrategy().label}; target ${cameraSettings.manualNightTargetLuma()}$vignette; boost ${cameraSettings.nightExposureBoostStops()} stops$sunriseSunset$cooldown$storageManagement"
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun useDeviceLocation() {
        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }
        val locationManager = getSystemService(LocationManager::class.java)
        val lastKnown = bestLastKnownLocation(locationManager)
        if (lastKnown != null) {
            saveDeviceLocation(lastKnown, "saved from last known location")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            updateStatus("No phone location available yet; open Maps or enable Location and try again")
            return
        }
        val provider = preferredEnabledProvider(locationManager)
        if (provider == null) {
            updateStatus("Location is disabled; enable phone Location and try again")
            return
        }
        updateStatus("Requesting phone location")
        locationManager.getCurrentLocation(provider, CancellationSignal(), mainExecutor) { location ->
            if (location == null) {
                updateStatus("No phone location available yet; try again after Location has a fix")
            } else {
                saveDeviceLocation(location, "saved from current location")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnownLocation(locationManager: LocationManager): Location? {
        return listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).mapNotNull { provider ->
            try {
                locationManager.getLastKnownLocation(provider)
            } catch (_: Exception) {
                null
            }
        }.maxByOrNull { it.time }
    }

    private fun preferredEnabledProvider(locationManager: LocationManager): String? {
        return listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).firstOrNull { provider ->
            try {
                locationManager.isProviderEnabled(provider)
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun saveDeviceLocation(location: Location, detail: String) {
        val latitude = location.latitude.coerceIn(-90.0, 90.0)
        val longitude = location.longitude.coerceIn(-180.0, 180.0)
        val latitudeText = String.format(Locale.US, "%.6f", latitude)
        val longitudeText = String.format(Locale.US, "%.6f", longitude)
        cameraSettings.setOverlayLatitude(latitude)
        cameraSettings.setOverlayLongitude(longitude)
        latitudeInput.setText(latitudeText)
        longitudeInput.setText(longitudeText)
        updateStatus("Location $detail: $latitudeText, $longitudeText")
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, FenetreCaptureService::class.java).setAction(action)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun updateStatus(message: String) {
        statusText.text = message
    }

    companion object {
        private const val SERVICE_RESTART_DELAY_MS = 500L
        private const val STATE_REFRESH_INTERVAL_MS = 5_000L
    }
}
