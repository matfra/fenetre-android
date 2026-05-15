package cam.fenetre.android

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class FenetreAdminServer(
    private val context: Context,
    private val rootDir: File,
    private val settings: FenetreCameraSettings,
    private val runtimeStatus: () -> FenetreRuntimeStatus,
    private val port: Int = settings.adminPort(),
) {
    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clientExecutor: ExecutorService = Executors.newCachedThreadPool()
    private val sunSchedule = FenetreSunSchedule(settings)
    private var previousCpuSample: CpuSample? = null
    private var previousProcessCpuSample: ProcessCpuSample? = null

    fun start() {
        if (running) {
            return
        }
        running = true
        acceptThread = thread(name = "fenetre-admin-server") {
            try {
                ServerSocket(port).use { socket ->
                    serverSocket = socket
                    while (running) {
                        val client = socket.accept()
                        clientExecutor.execute { handleClient(client) }
                    }
                }
            } catch (exception: Exception) {
                if (running) {
                    Log.e(TAG, "Admin server failed", exception)
                }
            } finally {
                running = false
                serverSocket = null
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        clientExecutor.shutdownNow()
    }

    fun url(host: String = settings.webHost()): String = "http://$host:$port/"

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                val input = client.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    writeResponse(client, 400, "Bad Request", "text/plain", "Bad Request\n".toByteArray())
                    return
                }
                val method = parts[0].uppercase(Locale.US)
                val path = parts[1].substringBefore("?").substringBefore("#")
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) {
                        break
                    }
                }

                if (method != "GET" && method != "HEAD") {
                    writeResponse(client, 405, "Method Not Allowed", "text/plain", "Method Not Allowed\n".toByteArray(), method == "HEAD")
                    return
                }

                when (path) {
                    "/" -> writeResponse(client, 200, "OK", "text/html; charset=utf-8", htmlStatus().toByteArray(StandardCharsets.UTF_8), method == "HEAD")
                    "/status.json" -> writeResponse(client, 200, "OK", "application/json", statusJson().toByteArray(StandardCharsets.UTF_8), method == "HEAD")
                    "/metrics" -> writeResponse(client, 200, "OK", "text/plain; version=0.0.4; charset=utf-8", metricsText().toByteArray(StandardCharsets.UTF_8), method == "HEAD")
                    else -> writeResponse(client, 404, "Not Found", "text/plain", "Not Found\n".toByteArray(), method == "HEAD")
                }
            } catch (exception: Exception) {
                Log.e(TAG, "Admin request failed", exception)
                try {
                    writeResponse(client, 500, "Internal Server Error", "text/plain", "Internal Server Error\n".toByteArray())
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun statusJson(): String {
        val runtime = runtimeStatus()
        val fileStatus = fileStatus()
        val thermal = FenetreThermal.status(context, settings)
        return """
            {
              "service": {
                "running": ${runtime.running},
                "capture_in_progress": ${runtime.captureInProgress},
                "thermal_paused": ${runtime.thermalPaused},
                "capture_mode": ${jsonString(runtime.captureMode.name.lowercase())},
                "exposure_mode": ${jsonString(runtime.exposureMode.name.lowercase())},
                "lens_mode": ${jsonString(runtime.lensMode.name.lowercase())},
                "rotation_degrees": ${runtime.rotationDegrees},
                "last_notification": ${jsonString(runtime.lastNotification)}
              },
              "camera": {
                "name": ${jsonString(settings.cameraName())},
                "deployment_name": ${jsonString(settings.deploymentName())},
                "public_base_url": ${jsonString(settings.publicBaseUrl())},
                "capture_interval_seconds": ${settings.captureIntervalSeconds()},
                "effective_capture_interval_seconds": ${sunSchedule.captureIntervalSeconds()},
                "cooldown_enabled": ${settings.cooldownEnabled()},
                "cooldown_battery_temperature_celsius": ${settings.cooldownBatteryTemperatureCelsius()},
                "sunrise_sunset_fast_enabled": ${settings.sunriseSunsetFastEnabled()},
                "sunrise_sunset_fast_active": ${sunSchedule.isSunriseSunsetWindow()},
                "sunrise_sunset_fast_interval_seconds": ${settings.sunriseSunsetFastIntervalSeconds()},
                "sunrise_offset_start_minutes": ${settings.sunriseOffsetStartMinutes()},
                "sunrise_offset_end_minutes": ${settings.sunriseOffsetEndMinutes()},
                "sunset_offset_start_minutes": ${settings.sunsetOffsetStartMinutes()},
                "sunset_offset_end_minutes": ${settings.sunsetOffsetEndMinutes()},
                "timestamp_overlay": ${settings.timestampOverlayEnabled()},
                "sun_path_overlay": ${settings.sunPathOverlayEnabled()},
                "overlay_timezone": ${jsonString(settings.overlayTimezone())},
                "overlay_lat": ${settings.overlayLatitude()},
                "overlay_lon": ${settings.overlayLongitude()}
              },
              "storage": {
                "root": ${jsonString(rootDir.absolutePath)},
                "latest_image_bytes": ${fileStatus.latestImageBytes},
                "latest_image_modified_ms": ${fileStatus.latestImageModifiedMs},
                "metadata_modified_ms": ${fileStatus.metadataModifiedMs},
                "metadata_captured_at_ms": ${fileStatus.metadataCapturedAtMs},
                "free_bytes": ${rootDir.freeSpace},
                "total_bytes": ${rootDir.totalSpace}
              },
              "thermal": {
                "cooldown_enabled": ${thermal.enabled},
                "paused": ${thermal.paused},
                "battery_temperature_celsius": ${thermal.batteryTemperatureCelsius ?: "null"},
                "threshold_celsius": ${thermal.thresholdCelsius},
                "android_thermal_status": ${thermal.androidThermalStatus ?: "null"}
              },
              "server": {
                "public_url": ${jsonString(settings.localWebUrl())},
                "admin_url": ${jsonString(url())},
                "android_model": ${jsonString(Build.MODEL ?: "unknown")},
                "android_release": ${jsonString(Build.VERSION.RELEASE ?: "unknown")},
                "android_sdk": ${Build.VERSION.SDK_INT}
              }
            }
        """.trimIndent() + "\n"
    }

    private fun metricsText(): String {
        val runtime = runtimeStatus()
        val fileStatus = fileStatus()
        val systemMetrics = systemMetrics()
        val now = System.currentTimeMillis()
        val ageSeconds = fileStatus.metadataCapturedAtMs?.let { maxOf(0L, (now - it) / 1000L) }
        val cameraLabels = """camera_name="${prometheusLabelValue(settings.cameraName())}""""
        val storageLabels = """device="android_app_data",fstype="app_data",mountpoint="${rootDir.absolutePath}""""
        val unameLabels = listOf(
            "domainname" to "(none)",
            "machine" to Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            "nodename" to (Build.MODEL ?: "unknown"),
            "release" to (Build.VERSION.RELEASE ?: "unknown"),
            "sysname" to "Android",
            "version" to "SDK ${Build.VERSION.SDK_INT} ${Build.FINGERPRINT ?: "unknown"}",
        ).joinToString(",") { (key, value) -> """$key="${prometheusLabelValue(value)}"""" }
        return buildString {
            appendLine("# HELP fenetre_android_service_running Whether the Android capture service is running.")
            appendLine("# TYPE fenetre_android_service_running gauge")
            appendLine("fenetre_android_service_running{$cameraLabels} ${if (runtime.running) 1 else 0}")
            appendLine("# HELP fenetre_android_capture_in_progress Whether a still capture is currently in progress.")
            appendLine("# TYPE fenetre_android_capture_in_progress gauge")
            appendLine("fenetre_android_capture_in_progress{$cameraLabels} ${if (runtime.captureInProgress) 1 else 0}")
            appendLine("# HELP fenetre_android_thermal_paused Whether capture and timelapse scheduling are paused for cooldown.")
            appendLine("# TYPE fenetre_android_thermal_paused gauge")
            appendLine("fenetre_android_thermal_paused{$cameraLabels} ${if (runtime.thermalPaused) 1 else 0}")
            appendLine("# HELP fenetre_android_latest_capture_age_seconds Age of the latest captured frame.")
            appendLine("# TYPE fenetre_android_latest_capture_age_seconds gauge")
            appendLine("fenetre_android_latest_capture_age_seconds{$cameraLabels} ${ageSeconds ?: -1}")
            appendLine("# HELP fenetre_android_latest_image_bytes Size of latest.jpg.")
            appendLine("# TYPE fenetre_android_latest_image_bytes gauge")
            appendLine("fenetre_android_latest_image_bytes{$cameraLabels} ${fileStatus.latestImageBytes}")
            appendLine("# HELP node_filesystem_avail_bytes Filesystem space available to non-root users in bytes.")
            appendLine("# TYPE node_filesystem_avail_bytes gauge")
            appendLine("node_filesystem_avail_bytes{$storageLabels} ${rootDir.freeSpace}")
            appendLine("# HELP node_filesystem_size_bytes Filesystem size in bytes.")
            appendLine("# TYPE node_filesystem_size_bytes gauge")
            appendLine("node_filesystem_size_bytes{$storageLabels} ${rootDir.totalSpace}")
            appendLine("# HELP fenetre_android_capture_interval_seconds Configured capture interval.")
            appendLine("# TYPE fenetre_android_capture_interval_seconds gauge")
            appendLine("fenetre_android_capture_interval_seconds{$cameraLabels} ${settings.captureIntervalSeconds()}")
            appendLine("# HELP fenetre_android_effective_capture_interval_seconds Current effective capture interval.")
            appendLine("# TYPE fenetre_android_effective_capture_interval_seconds gauge")
            appendLine("fenetre_android_effective_capture_interval_seconds{$cameraLabels} ${sunSchedule.captureIntervalSeconds()}")
            appendLine("# HELP fenetre_android_cooldown_battery_temperature_celsius Configured battery temperature threshold for cooldown.")
            appendLine("# TYPE fenetre_android_cooldown_battery_temperature_celsius gauge")
            appendLine("fenetre_android_cooldown_battery_temperature_celsius{$cameraLabels} ${settings.cooldownBatteryTemperatureCelsius()}")
            appendLine("# HELP fenetre_android_cooldown_enabled Whether thermal cooldown protection is enabled.")
            appendLine("# TYPE fenetre_android_cooldown_enabled gauge")
            appendLine("fenetre_android_cooldown_enabled{$cameraLabels} ${if (settings.cooldownEnabled()) 1 else 0}")
            appendLine("# HELP fenetre_android_sunrise_sunset_fast_enabled Whether fast sunrise/sunset capture is enabled.")
            appendLine("# TYPE fenetre_android_sunrise_sunset_fast_enabled gauge")
            appendLine("fenetre_android_sunrise_sunset_fast_enabled{$cameraLabels} ${if (settings.sunriseSunsetFastEnabled()) 1 else 0}")
            appendLine("# HELP fenetre_android_sunrise_sunset_fast_active Whether the current time is in a fast sunrise/sunset window.")
            appendLine("# TYPE fenetre_android_sunrise_sunset_fast_active gauge")
            appendLine("fenetre_android_sunrise_sunset_fast_active{$cameraLabels} ${if (sunSchedule.isSunriseSunsetWindow()) 1 else 0}")
            appendLine("# HELP node_uname_info Labeled system information as provided by the uname system call.")
            appendLine("# TYPE node_uname_info gauge")
            appendLine("node_uname_info{$unameLabels} 1")
            appendLine("# HELP node_memory_MemTotal_bytes Memory information field MemTotal_bytes.")
            appendLine("# TYPE node_memory_MemTotal_bytes gauge")
            appendLine("node_memory_MemTotal_bytes ${systemMetrics.memoryTotalBytes ?: -1}")
            appendLine("# HELP node_memory_MemAvailable_bytes Memory information field MemAvailable_bytes.")
            appendLine("# TYPE node_memory_MemAvailable_bytes gauge")
            appendLine("node_memory_MemAvailable_bytes ${systemMetrics.memoryAvailableBytes ?: -1}")
            appendLine("# HELP node_load1 1m load average.")
            appendLine("# TYPE node_load1 gauge")
            appendLine("node_load1 ${systemMetrics.loadAverage1m ?: -1.0}")
            appendLine("# HELP fenetre_android_node_cpu_usage_percent System CPU usage since the previous scrape; node exporter normally exposes node_cpu_seconds_total counters instead.")
            appendLine("# TYPE fenetre_android_node_cpu_usage_percent gauge")
            appendLine("fenetre_android_node_cpu_usage_percent{$cameraLabels} ${systemMetrics.cpuUsagePercent ?: -1.0}")
            appendLine("# HELP fenetre_android_process_cpu_time_seconds App process CPU time.")
            appendLine("# TYPE fenetre_android_process_cpu_time_seconds counter")
            appendLine("fenetre_android_process_cpu_time_seconds{$cameraLabels} ${systemMetrics.processCpuTimeSeconds}")
            appendLine("# HELP fenetre_android_process_cpu_usage_percent App process CPU usage since the previous scrape, normalized across CPU cores.")
            appendLine("# TYPE fenetre_android_process_cpu_usage_percent gauge")
            appendLine("fenetre_android_process_cpu_usage_percent{$cameraLabels} ${systemMetrics.processCpuUsagePercent ?: -1.0}")
            appendLine("# HELP node_cpu_scaling_frequency_hertz Current scaled CPU thread frequency in hertz.")
            appendLine("# TYPE node_cpu_scaling_frequency_hertz gauge")
            systemMetrics.cpuFrequenciesHz.forEach { (cpu, frequencyHz) ->
                appendLine("""node_cpu_scaling_frequency_hertz{cpu="$cpu"} $frequencyHz""")
            }
            appendLine("# HELP fenetre_android_process_memory_pss_bytes App process proportional set size.")
            appendLine("# TYPE fenetre_android_process_memory_pss_bytes gauge")
            appendLine("fenetre_android_process_memory_pss_bytes{$cameraLabels} ${systemMetrics.processMemoryPssBytes ?: -1}")
            appendLine("# HELP fenetre_android_runtime_heap_used_bytes App runtime heap bytes currently used.")
            appendLine("# TYPE fenetre_android_runtime_heap_used_bytes gauge")
            appendLine("fenetre_android_runtime_heap_used_bytes{$cameraLabels} ${systemMetrics.runtimeHeapUsedBytes}")
            appendLine("# HELP fenetre_android_runtime_heap_max_bytes App runtime max heap bytes.")
            appendLine("# TYPE fenetre_android_runtime_heap_max_bytes gauge")
            appendLine("fenetre_android_runtime_heap_max_bytes{$cameraLabels} ${systemMetrics.runtimeHeapMaxBytes}")
            appendLine("# HELP node_power_supply_capacity Battery capacity percentage.")
            appendLine("# TYPE node_power_supply_capacity gauge")
            appendLine("""node_power_supply_capacity{power_supply="battery"} ${systemMetrics.batteryLevelPercent ?: -1.0}""")
            appendLine("# HELP node_power_supply_temp_celsius Battery temperature in Celsius.")
            appendLine("# TYPE node_power_supply_temp_celsius gauge")
            appendLine("""node_power_supply_temp_celsius{power_supply="battery"} ${systemMetrics.batteryTemperatureCelsius ?: -1.0}""")
            appendLine("# HELP node_thermal_zone_state Android thermal status enum.")
            appendLine("# TYPE node_thermal_zone_state gauge")
            appendLine("""node_thermal_zone_state{zone="android"} ${systemMetrics.thermalStatus ?: -1}""")
        }
    }

    private fun htmlStatus(): String {
        val runtime = runtimeStatus()
        val fileStatus = fileStatus()
        val latestAge = fileStatus.metadataCapturedAtMs?.let {
            "${maxOf(0L, (System.currentTimeMillis() - it) / 1000L)}s"
        } ?: "n/a"
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>${htmlEscape(settings.deploymentName())} admin</title>
              <style>
                :root { color-scheme: dark; font-family: Inter, Roboto, Arial, sans-serif; background: #080b10; color: #f8fafc; }
                body { margin: 0; padding: 28px; background: #080b10; }
                main { max-width: 860px; margin: 0 auto; }
                h1 { margin: 0 0 18px; font-size: 26px; }
                dl { display: grid; grid-template-columns: minmax(160px, 240px) 1fr; gap: 8px 16px; }
                dt { color: #94a3b8; }
                dd { margin: 0; }
                a { color: #93c5fd; }
              </style>
            </head>
            <body>
              <main>
                <h1>${htmlEscape(settings.deploymentName())} admin</h1>
                <dl>
                  <dt>Service</dt><dd>${if (runtime.running) "running" else "stopped"}</dd>
                  <dt>Capture</dt><dd>${if (runtime.captureInProgress) "in progress" else "idle"}</dd>
                  <dt>Thermal cooldown</dt><dd>${if (runtime.thermalPaused) "paused" else "normal"}</dd>
                  <dt>Lens</dt><dd>${htmlEscape(runtime.lensMode.label)}</dd>
                  <dt>Exposure</dt><dd>${htmlEscape(runtime.exposureMode.label)} / ${htmlEscape(runtime.captureMode.label)}</dd>
                  <dt>Latest age</dt><dd>$latestAge</dd>
                  <dt>Latest size</dt><dd>${fileStatus.latestImageBytes} bytes</dd>
                  <dt>Storage free</dt><dd>${rootDir.freeSpace} bytes</dd>
                  <dt>Public UI</dt><dd><a href="${htmlEscape(settings.localWebUrl())}">${htmlEscape(settings.localWebUrl())}</a></dd>
                  <dt>Status JSON</dt><dd><a href="/status.json">/status.json</a></dd>
                  <dt>Metrics</dt><dd><a href="/metrics">/metrics</a></dd>
                </dl>
              </main>
            </body>
            </html>
        """.trimIndent()
    }

    private fun fileStatus(): FenetreFileStatus {
        val cameraDir = File(File(rootDir, "photos"), settings.cameraName())
        val latest = File(cameraDir, "latest.jpg")
        val metadata = File(cameraDir, "metadata.json")
        val metadataText = if (metadata.exists()) metadata.readText() else ""
        val capturedAtMs = Regex(""""captured_at_ms"\s*:\s*(\d+)""")
            .find(metadataText)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
        return FenetreFileStatus(
            latestImageBytes = if (latest.exists()) latest.length() else 0L,
            latestImageModifiedMs = if (latest.exists()) latest.lastModified() else null,
            metadataModifiedMs = if (metadata.exists()) metadata.lastModified() else null,
            metadataCapturedAtMs = capturedAtMs,
        )
    }

    private fun systemMetrics(): FenetreSystemMetrics {
        val meminfo = readMeminfo()
        val loadAverage = readLoadAverage1m()
        val cpuUsage = sampleCpuUsagePercent()
        val processCpu = sampleProcessCpuUsage()
        val cpuFrequencies = readCpuFrequenciesHz()
        val processMemoryPssBytes = readProcessMemoryPssBytes()
        val runtime = Runtime.getRuntime()
        val battery = FenetreThermal.batteryStatus(context)
        val thermalStatus = FenetreThermal.status(context, settings).androidThermalStatus
        return FenetreSystemMetrics(
            memoryTotalBytes = meminfo["MemTotal"],
            memoryAvailableBytes = meminfo["MemAvailable"],
            loadAverage1m = loadAverage,
            cpuUsagePercent = cpuUsage,
            processCpuTimeSeconds = processCpu.first,
            processCpuUsagePercent = processCpu.second,
            cpuFrequenciesHz = cpuFrequencies,
            processMemoryPssBytes = processMemoryPssBytes,
            runtimeHeapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            runtimeHeapMaxBytes = runtime.maxMemory(),
            batteryLevelPercent = battery.first,
            batteryTemperatureCelsius = battery.second,
            thermalStatus = thermalStatus,
        )
    }

    private fun readMeminfo(): Map<String, Long> {
        return try {
            val result = mutableMapOf<String, Long>()
            val file = File("/proc/meminfo")
            if (!file.exists()) {
                return result
            }
            file.forEachLine { line ->
                val parts = line.split(Regex("""\s+"""))
                if (parts.size >= 2) {
                    val key = parts[0].trimEnd(':')
                    val valueKb = parts[1].toLongOrNull()
                    if (valueKb != null) {
                        result[key] = valueKb * 1024L
                    }
                }
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun readLoadAverage1m(): Double? {
        return try {
            val file = File("/proc/loadavg")
            if (!file.exists()) {
                return null
            }
            file.readText().trim().split(Regex("""\s+""")).firstOrNull()?.toDoubleOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun sampleCpuUsagePercent(): Double? {
        return try {
            val sample = readCpuSample() ?: return null
            val previous = previousCpuSample
            previousCpuSample = sample
            if (previous == null) {
                return null
            }
            val totalDelta = sample.total - previous.total
            val idleDelta = sample.idle - previous.idle
            if (totalDelta <= 0L) {
                return null
            }
            ((totalDelta - idleDelta).toDouble() / totalDelta.toDouble()) * 100.0
        } catch (_: Exception) {
            null
        }
    }

    private fun readCpuSample(): CpuSample? {
        return try {
            val line = File("/proc/stat").useLines { lines ->
                lines.firstOrNull { it.startsWith("cpu ") }
            } ?: return null
            val values = line.trim().split(Regex("""\s+""")).drop(1).mapNotNull { it.toLongOrNull() }
            if (values.size < 4) {
                return null
            }
            val idle = values.getOrNull(3).orZero() + values.getOrNull(4).orZero()
            CpuSample(total = values.sum(), idle = idle)
        } catch (_: Exception) {
            null
        }
    }

    private fun readCpuFrequenciesHz(): Map<String, Long> {
        return try {
            val result = linkedMapOf<String, Long>()
            val cpuRoot = File("/sys/devices/system/cpu")
            cpuRoot.listFiles { file -> file.isDirectory && CPU_DIR_PATTERN.matches(file.name) }
                ?.sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: Int.MAX_VALUE }
                ?.forEach { cpuDir ->
                    val freqKhz = File(cpuDir, "cpufreq/scaling_cur_freq").readTextOrNull()?.trim()?.toLongOrNull()
                    if (freqKhz != null) {
                        result[cpuDir.name.removePrefix("cpu")] = freqKhz * 1000L
                    }
                }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun sampleProcessCpuUsage(): Pair<Double, Double?> {
        val sample = ProcessCpuSample(
            cpuTimeMs = android.os.Process.getElapsedCpuTime(),
            elapsedMs = SystemClock.elapsedRealtime(),
        )
        val previous = previousProcessCpuSample
        previousProcessCpuSample = sample
        val cpuTimeSeconds = sample.cpuTimeMs / 1000.0
        if (previous == null) {
            return cpuTimeSeconds to null
        }
        val cpuDelta = sample.cpuTimeMs - previous.cpuTimeMs
        val elapsedDelta = sample.elapsedMs - previous.elapsedMs
        if (cpuDelta < 0L || elapsedDelta <= 0L) {
            return cpuTimeSeconds to null
        }
        val coreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val usagePercent = (cpuDelta.toDouble() / (elapsedDelta.toDouble() * coreCount.toDouble())) * 100.0
        return cpuTimeSeconds to usagePercent
    }

    private fun readProcessMemoryPssBytes(): Long? {
        return try {
            val memoryInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memoryInfo)
            memoryInfo.totalPss.toLong() * 1024L
        } catch (_: Exception) {
            null
        }
    }

    private fun File.readTextOrNull(): String? {
        return try {
            readText()
        } catch (_: Exception) {
            null
        }
    }

    private fun Long?.orZero(): Long = this ?: 0L

    private fun writeResponse(
        socket: Socket,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: ByteArray,
        headOnly: Boolean = false,
    ) {
        val output = BufferedOutputStream(socket.getOutputStream())
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "\r\n"
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        if (!headOnly) {
            output.write(body)
        }
        output.flush()
    }

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

    private fun prometheusLabelValue(value: String): String {
        return buildString {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    else -> append(char)
                }
            }
        }
    }

    private fun htmlEscape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    companion object {
        private const val TAG = "FenetreAdminServer"
        private val CPU_DIR_PATTERN = Regex("""cpu\d+""")
    }
}

data class FenetreRuntimeStatus(
    val running: Boolean,
    val captureInProgress: Boolean,
    val lensMode: LensMode,
    val exposureMode: ExposureMode,
    val captureMode: ExposureMode,
    val rotationDegrees: Int,
    val lastNotification: String,
    val thermalPaused: Boolean,
)

private data class FenetreFileStatus(
    val latestImageBytes: Long,
    val latestImageModifiedMs: Long?,
    val metadataModifiedMs: Long?,
    val metadataCapturedAtMs: Long?,
)

private data class FenetreSystemMetrics(
    val memoryTotalBytes: Long?,
    val memoryAvailableBytes: Long?,
    val loadAverage1m: Double?,
    val cpuUsagePercent: Double?,
    val processCpuTimeSeconds: Double,
    val processCpuUsagePercent: Double?,
    val cpuFrequenciesHz: Map<String, Long>,
    val processMemoryPssBytes: Long?,
    val runtimeHeapUsedBytes: Long,
    val runtimeHeapMaxBytes: Long,
    val batteryLevelPercent: Double?,
    val batteryTemperatureCelsius: Double?,
    val thermalStatus: Int?,
)

private data class CpuSample(
    val total: Long,
    val idle: Long,
)

private data class ProcessCpuSample(
    val cpuTimeMs: Long,
    val elapsedMs: Long,
)
