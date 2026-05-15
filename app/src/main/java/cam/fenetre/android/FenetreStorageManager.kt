package cam.fenetre.android

import android.util.Log
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

class FenetreStorageManager(
    private val storage: FenetreStorage,
    private val settings: FenetreCameraSettings,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fenetre-storage-manager")
    }
    private val lastStatusRef = AtomicReference(StorageManagementStatus.empty(storage.rootDir()))

    @Volatile
    private var running = false

    @Volatile
    private var inProgress = false

    @Volatile
    private var lastRunStartedAtMs = 0L

    fun maybeSchedule(force: Boolean = false) {
        if (!settings.storageManagementEnabled() && !force) {
            publishScanOnlyStatus()
            return
        }
        if (inProgress) {
            return
        }
        val now = System.currentTimeMillis()
        val intervalMs = settings.storageManagementCheckIntervalSeconds() * 1000L
        if (!force && lastRunStartedAtMs > 0L && now - lastRunStartedAtMs < intervalMs) {
            return
        }
        inProgress = true
        lastRunStartedAtMs = now
        executor.execute {
            try {
                val status = runOnce(force)
                lastStatusRef.set(status)
            } catch (exception: Exception) {
                Log.e(TAG, "Storage management failed", exception)
                lastStatusRef.set(lastStatus().copy(
                    inProgress = false,
                    lastError = exception.message ?: exception.javaClass.simpleName,
                    lastCompletedAtMs = System.currentTimeMillis(),
                ))
            } finally {
                inProgress = false
            }
        }
    }

    fun lastStatus(): StorageManagementStatus = lastStatusRef.get().copy(inProgress = inProgress)

    fun stop() {
        executor.shutdownNow()
    }

    private fun runOnce(force: Boolean): StorageManagementStatus {
        val rootDir = storage.rootDir()
        val cameraDir = storage.cameraDir()
        val dryRun = settings.storageManagementDryRun()
        val archiveResult = if (settings.storageArchiveEnabled()) {
            archiveCompletedDays(cameraDir, dryRun)
        } else {
            ArchiveResult()
        }
        val beforeBytes = directorySize(rootDir)
        val deleteResult = if (settings.storageManagementEnabled() || force) {
            enforceSizeLimit(rootDir, beforeBytes, dryRun)
        } else {
            DeleteResult()
        }
        val afterBytes = if (deleteResult.deletedBytes > 0L) directorySize(rootDir) else beforeBytes
        val scan = scanCameraDirectory(cameraDir)
        return StorageManagementStatus(
            enabled = settings.storageManagementEnabled(),
            dryRun = dryRun,
            archiveEnabled = settings.storageArchiveEnabled(),
            inProgress = false,
            rootPath = rootDir.absolutePath,
            maxBytes = maxSizeBytes(),
            sizeBytes = afterBytes,
            freeBytes = rootDir.freeSpace,
            totalBytes = rootDir.totalSpace,
            dayDirectoryCount = scan.dayDirectoryCount,
            archivedDayDirectoryCount = scan.archivedDayDirectoryCount,
            timelapseDayDirectoryCount = scan.timelapseDayDirectoryCount,
            daylightDayDirectoryCount = scan.daylightDayDirectoryCount,
            archivedDaysThisRun = archiveResult.archivedDays,
            deletedDayDirectoriesThisRun = deleteResult.deletedDirectories,
            deletedBytesThisRun = deleteResult.deletedBytes,
            dryRunDeletedDayDirectoriesThisRun = deleteResult.dryRunDeletedDirectories,
            dryRunDeletedBytesThisRun = deleteResult.dryRunDeletedBytes,
            lastStartedAtMs = lastRunStartedAtMs,
            lastCompletedAtMs = System.currentTimeMillis(),
            lastError = null,
        )
    }

    private fun publishScanOnlyStatus() {
        val rootDir = storage.rootDir()
        val scan = scanCameraDirectory(storage.cameraDir())
        lastStatusRef.set(lastStatus().copy(
            enabled = settings.storageManagementEnabled(),
            dryRun = settings.storageManagementDryRun(),
            archiveEnabled = settings.storageArchiveEnabled(),
            rootPath = rootDir.absolutePath,
            maxBytes = maxSizeBytes(),
            sizeBytes = directorySize(rootDir),
            freeBytes = rootDir.freeSpace,
            totalBytes = rootDir.totalSpace,
            dayDirectoryCount = scan.dayDirectoryCount,
            archivedDayDirectoryCount = scan.archivedDayDirectoryCount,
            timelapseDayDirectoryCount = scan.timelapseDayDirectoryCount,
            daylightDayDirectoryCount = scan.daylightDayDirectoryCount,
        ))
    }

    private fun archiveCompletedDays(cameraDir: File, dryRun: Boolean): ArchiveResult {
        var archivedDays = 0
        for (dayDir in sortedDayDirs(cameraDir)) {
            if (!isOldEnoughToArchive(dayDir.name)) {
                continue
            }
            if (File(dayDir, ARCHIVED_MARKER).isFile) {
                continue
            }
            if (!hasDaylightBand(dayDir) || !hasDailyTimelapse(dayDir)) {
                continue
            }
            if (archiveDayDirectory(dayDir, dryRun)) {
                archivedDays += 1
            }
        }
        return ArchiveResult(archivedDays = archivedDays)
    }

    private fun archiveDayDirectory(dayDir: File, dryRun: Boolean): Boolean {
        val jpegFiles = dayDir.listFiles { file ->
            file.isFile && file.extension.equals("jpg", ignoreCase = true)
        }?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name }).orEmpty()
        val filesToKeep = settings.storageArchiveFilesToKeep()
        if (jpegFiles.size <= filesToKeep) {
            Log.w(TAG, "${dayDir.absolutePath} has ${jpegFiles.size} photos, fewer than archive target $filesToKeep")
            return false
        }
        val keepInterval = max(1, jpegFiles.size / filesToKeep)
        var deletedCount = 0
        jpegFiles.forEachIndexed { index, file ->
            if (index % keepInterval == 0) {
                return@forEachIndexed
            }
            if (!dryRun && file.delete()) {
                deletedCount += 1
            } else if (dryRun) {
                deletedCount += 1
            }
        }
        if (!dryRun) {
            File(dayDir, ARCHIVED_MARKER).writeText("${System.currentTimeMillis()}\n")
        }
        Log.i(TAG, "Archived ${dayDir.name}: ${if (dryRun) "would delete" else "deleted"} $deletedCount/${jpegFiles.size} JPEG files")
        return true
    }

    private fun enforceSizeLimit(rootDir: File, currentSizeBytes: Long, dryRun: Boolean): DeleteResult {
        val limitBytes = maxSizeBytes()
        if (currentSizeBytes <= limitBytes) {
            return DeleteResult()
        }
        var remainingBytes = currentSizeBytes
        var deletedDirectories = 0
        var deletedBytes = 0L
        var dryRunDeletedDirectories = 0
        var dryRunDeletedBytes = 0L
        val dayDirs = rootDir.resolve("photos")
            .listFiles { file -> file.isDirectory }
            ?.flatMap { cameraDir -> sortedDayDirs(cameraDir) }
            ?.sortedBy { it.name }
            .orEmpty()
        for (dayDir in dayDirs) {
            if (remainingBytes <= limitBytes) {
                break
            }
            val size = directorySize(dayDir)
            if (dryRun) {
                dryRunDeletedDirectories += 1
                dryRunDeletedBytes += size
            } else if (dayDir.deleteRecursively()) {
                deletedDirectories += 1
                deletedBytes += size
            }
            remainingBytes -= size
            Log.i(TAG, "${if (dryRun) "[DRY RUN] Would delete" else "Deleted"} ${dayDir.absolutePath} to free ${size / (1024 * 1024)} MB")
        }
        return DeleteResult(
            deletedDirectories = deletedDirectories,
            deletedBytes = deletedBytes,
            dryRunDeletedDirectories = dryRunDeletedDirectories,
            dryRunDeletedBytes = dryRunDeletedBytes,
        )
    }

    private fun scanCameraDirectory(cameraDir: File): StorageDirectoryScan {
        var days = 0
        var archived = 0
        var timelapse = 0
        var daylight = 0
        for (dayDir in sortedDayDirs(cameraDir)) {
            days += 1
            if (File(dayDir, ARCHIVED_MARKER).isFile) {
                archived += 1
            }
            if (hasDailyTimelapse(dayDir)) {
                timelapse += 1
            }
            if (hasDaylightBand(dayDir)) {
                daylight += 1
            }
        }
        return StorageDirectoryScan(days, archived, timelapse, daylight)
    }

    private fun sortedDayDirs(cameraDir: File): List<File> {
        return cameraDir.listFiles { file -> file.isDirectory && DAY_DIR_PATTERN.matches(file.name) }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    private fun isOldEnoughToArchive(dayName: String): Boolean {
        val date = try {
            LocalDate.parse(dayName)
        } catch (_: DateTimeParseException) {
            return false
        }
        val zone = try {
            ZoneId.of(settings.overlayTimezone())
        } catch (_: Exception) {
            ZoneId.systemDefault()
        }
        val today = ZonedDateTime.now(zone).toLocalDate()
        return date.plusDays(settings.storageArchiveAfterDays().toLong()).isBefore(today)
    }

    private fun hasDailyTimelapse(dayDir: File): Boolean {
        return listOf("mp4", "webm").any { extension ->
            val file = File(dayDir, "${dayDir.name}.$extension")
            file.isFile && file.length() > MIN_TIMELAPSE_BYTES
        }
    }

    private fun hasDaylightBand(dayDir: File): Boolean = File(dayDir, "daylight.png").isFile

    private fun directorySize(file: File): Long {
        if (!file.exists()) {
            return 0L
        }
        if (file.isFile) {
            return file.length()
        }
        return file.listFiles()?.sumOf { child -> directorySize(child) } ?: 0L
    }

    private fun maxSizeBytes(): Long = settings.storageManagementMaxSizeGb().toLong() * 1024L * 1024L * 1024L

    companion object {
        private const val TAG = "FenetreStorageManager"
        private const val ARCHIVED_MARKER = "archived"
        private const val MIN_TIMELAPSE_BYTES = 1024L * 1024L
        private val DAY_DIR_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}

data class StorageManagementStatus(
    val enabled: Boolean,
    val dryRun: Boolean,
    val archiveEnabled: Boolean,
    val inProgress: Boolean,
    val rootPath: String,
    val maxBytes: Long,
    val sizeBytes: Long,
    val freeBytes: Long,
    val totalBytes: Long,
    val dayDirectoryCount: Int,
    val archivedDayDirectoryCount: Int,
    val timelapseDayDirectoryCount: Int,
    val daylightDayDirectoryCount: Int,
    val archivedDaysThisRun: Int,
    val deletedDayDirectoriesThisRun: Int,
    val deletedBytesThisRun: Long,
    val dryRunDeletedDayDirectoriesThisRun: Int,
    val dryRunDeletedBytesThisRun: Long,
    val lastStartedAtMs: Long,
    val lastCompletedAtMs: Long,
    val lastError: String?,
) {
    companion object {
        fun empty(rootDir: File): StorageManagementStatus {
            return StorageManagementStatus(
                enabled = false,
                dryRun = true,
                archiveEnabled = true,
                inProgress = false,
                rootPath = rootDir.absolutePath,
                maxBytes = 0L,
                sizeBytes = 0L,
                freeBytes = rootDir.freeSpace,
                totalBytes = rootDir.totalSpace,
                dayDirectoryCount = 0,
                archivedDayDirectoryCount = 0,
                timelapseDayDirectoryCount = 0,
                daylightDayDirectoryCount = 0,
                archivedDaysThisRun = 0,
                deletedDayDirectoriesThisRun = 0,
                deletedBytesThisRun = 0L,
                dryRunDeletedDayDirectoriesThisRun = 0,
                dryRunDeletedBytesThisRun = 0L,
                lastStartedAtMs = 0L,
                lastCompletedAtMs = 0L,
                lastError = null,
            )
        }
    }
}

private data class ArchiveResult(
    val archivedDays: Int = 0,
)

private data class DeleteResult(
    val deletedDirectories: Int = 0,
    val deletedBytes: Long = 0L,
    val dryRunDeletedDirectories: Int = 0,
    val dryRunDeletedBytes: Long = 0L,
)

private data class StorageDirectoryScan(
    val dayDirectoryCount: Int,
    val archivedDayDirectoryCount: Int,
    val timelapseDayDirectoryCount: Int,
    val daylightDayDirectoryCount: Int,
)
