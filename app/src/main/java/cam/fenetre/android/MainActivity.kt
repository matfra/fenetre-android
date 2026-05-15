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
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var statusText: TextView
    private lateinit var storage: FenetreStorage
    private lateinit var cameraSettings: FenetreCameraSettings
    private lateinit var latitudeInput: EditText
    private lateinit var longitudeInput: EditText

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

        content.addView(sectionTitle("Capture"))
        content.addView(actionGrid())

        content.addView(sectionTitle("Lens"))
        content.addView(lensGroup())

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
        content.addView(helpText("When enabled, capture and timelapse work pause while the battery is at or above this temperature."))

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
            "; cooldown ${cameraSettings.cooldownBatteryTemperatureCelsius()}C"
        } else {
            ""
        }
        val storageManagement = if (cameraSettings.storageManagementEnabled()) {
            "; storage ${cameraSettings.storageManagementMaxSizeGb()}GB"
        } else {
            ""
        }
        return "Camera ${cameraSettings.cameraName()}; lens ${cameraSettings.lensMode().label}; exposure ${cameraSettings.exposureMode().label}; rotate ${cameraSettings.rotationDegrees()}; every ${cameraSettings.captureIntervalSeconds()}s; daily ${cameraSettings.dailyTimelapseEncoderMode().label}$sunriseSunset$cooldown$storageManagement"
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
    }
}
