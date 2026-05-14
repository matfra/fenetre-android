package cam.fenetre.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import java.io.File
import java.time.YearMonth
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class FenetreDaylight(private val storage: FenetreStorage) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val minuteStats = mutableMapOf<String, MutableMap<Int, ColorStats>>()

    fun observe(photoFile: File) {
        val dayAndMinute = dayAndMinute(photoFile) ?: return
        val color = averageSkyColor(photoFile) ?: return
        synchronized(minuteStats) {
            val dayStats = minuteStats.getOrPut(dayAndMinute.day) { mutableMapOf() }
            dayStats.getOrPut(dayAndMinute.minute) { ColorStats() }.add(color)
        }
        scheduleCurrentDay()
    }

    fun scheduleCurrentDay() {
        val dayDir = storage.currentDayDir()
        schedule {
            createDailyBand(dayDir, preferObservedStats = true)
            createMonthlyImage(dayDir.name.substring(0, 7))
            writeDaylightBrowser()
        }
    }

    fun scheduleCompletedDays() {
        val currentDay = storage.currentDayDir().name
        schedule {
            storage.dayDirs()
                .filter { it.name < currentDay && !File(it, DAYLIGHT_FILE).exists() }
                .forEach { dayDir ->
                    createDailyBand(dayDir, preferObservedStats = false)
                    createMonthlyImage(dayDir.name.substring(0, 7))
                }
            writeDaylightBrowser()
        }
    }

    fun scheduleFullRebuild() {
        schedule {
            val months = mutableSetOf<String>()
            storage.dayDirs().forEach { dayDir ->
                createDailyBand(dayDir, preferObservedStats = false)
                months.add(dayDir.name.substring(0, 7))
            }
            months.forEach { createMonthlyImage(it) }
            writeDaylightBrowser()
        }
    }

    fun stop() {
        executor.shutdownNow()
    }

    private fun schedule(block: () -> Unit) {
        if (!running.compareAndSet(false, true)) {
            return
        }
        executor.execute {
            try {
                block()
            } catch (exception: Exception) {
                Log.e(TAG, "Daylight generation failed", exception)
            } finally {
                running.set(false)
            }
        }
    }

    private fun createDailyBand(dayDir: File, preferObservedStats: Boolean) {
        val colors = if (preferObservedStats) {
            observedColors(dayDir.name)
        } else {
            emptyMap()
        }.ifEmpty {
            scanDailyColors(dayDir)
        }

        val bitmap = Bitmap.createBitmap(1, DAILY_BAND_HEIGHT, Bitmap.Config.ARGB_8888)
        var lastColor = DEFAULT_SKY_COLOR
        for (minute in 0 until DAILY_BAND_HEIGHT) {
            val color = colors[minute] ?: lastColor
            lastColor = color
            bitmap.setPixel(0, minute, color)
        }
        File(dayDir, DAYLIGHT_FILE).outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        bitmap.recycle()
        File(dayDir, "daylight.json").writeText(
            """
            {
              "minutes": ${colors.size},
              "updated_at_ms": ${System.currentTimeMillis()}
            }
            """.trimIndent() + "\n"
        )
    }

    private fun observedColors(day: String): Map<Int, Int> {
        synchronized(minuteStats) {
            return minuteStats[day]
                ?.mapValues { it.value.color() }
                .orEmpty()
        }
    }

    private fun scanDailyColors(dayDir: File): Map<Int, Int> {
        val stats = mutableMapOf<Int, ColorStats>()
        val images = dayDir.listFiles { file ->
            file.isFile && file.extension.lowercase(Locale.US) == "jpg" && file.length() > 0
        }?.sortedBy { it.name }.orEmpty()
        images.forEach { image ->
            val dayAndMinute = dayAndMinute(image) ?: return@forEach
            val color = averageSkyColor(image) ?: return@forEach
            stats.getOrPut(dayAndMinute.minute) { ColorStats() }.add(color)
        }
        return stats.mapValues { it.value.color() }
    }

    private fun createMonthlyImage(yearMonth: String) {
        val parsed = YearMonth.parse(yearMonth)
        val outputDir = File(storage.cameraDir(), "daylight")
        outputDir.mkdirs()
        val bitmap = Bitmap.createBitmap(parsed.lengthOfMonth(), DAILY_BAND_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val defaultBand = Bitmap.createBitmap(1, DAILY_BAND_HEIGHT, Bitmap.Config.ARGB_8888).apply {
            eraseColor(DEFAULT_SKY_COLOR)
        }
        for (day in 1..parsed.lengthOfMonth()) {
            val dayName = "%s-%02d".format(Locale.US, yearMonth, day)
            val bandFile = File(File(storage.cameraDir(), dayName), DAYLIGHT_FILE)
            val band = if (bandFile.exists()) {
                BitmapFactory.decodeFile(bandFile.absolutePath) ?: defaultBand
            } else {
                defaultBand
            }
            canvas.drawBitmap(band, day - 1f, 0f, null)
            if (band !== defaultBand) {
                band.recycle()
            }
        }
        File(outputDir, "$yearMonth.png").outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        bitmap.recycle()
        defaultBand.recycle()
    }

    private fun writeDaylightBrowser() {
        val daylightDir = File(storage.cameraDir(), "daylight")
        val monthFiles = daylightDir.listFiles { file ->
            file.isFile && file.extension.lowercase(Locale.US) == "png"
        }?.sortedByDescending { it.name }.orEmpty()
        File(storage.cameraDir(), "daylight.html").writeText(
            buildString {
                appendLine("<!doctype html>")
                appendLine("<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                appendLine("<title>Fenetre daylight</title>")
                appendLine("<style>")
                appendLine("body{margin:0;background:#05070a;color:#f5f7fb;font-family:Arial,sans-serif;display:flex;gap:12px;padding:12px}")
                appendLine(".labels{width:52px;position:sticky;left:0;background:#05070a}.label{height:60px;font-size:12px;color:#9ca3af}")
                appendLine(".month{display:flex;align-items:flex-start;gap:8px;margin-right:18px}.month img{height:1440px;image-rendering:auto}.name{writing-mode:vertical-rl;color:#d1d5db}")
                appendLine(".bands{display:flex;align-items:flex-start}")
                appendLine("a{color:inherit}")
                appendLine("</style></head><body>")
                appendLine("<div class=\"labels\">")
                for (hour in 0 until 24) {
                    val label = when {
                        hour == 0 -> "12 AM"
                        hour < 12 -> "$hour AM"
                        hour == 12 -> "12 PM"
                        else -> "${hour - 12} PM"
                    }
                    appendLine("<div class=\"label\">$label</div>")
                }
                appendLine("</div><div class=\"bands\">")
                monthFiles.forEach { file ->
                    val month = file.nameWithoutExtension
                    appendLine("<div class=\"month\"><a href=\"daylight/$month.png\"><img src=\"daylight/${file.name}\" alt=\"$month\"></a><div class=\"name\">$month</div></div>")
                }
                appendLine("</div></body></html>")
            }
        )
    }

    private fun averageSkyColor(imageFile: File): Int? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        val sampleSize = maxOf(1, minOf(bounds.outWidth, bounds.outHeight) / SKY_SAMPLE_SIZE)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath, options) ?: return null
        val crop = skyCrop(bitmap.width, bitmap.height)
        val pixels = IntArray(crop.width() * crop.height())
        bitmap.getPixels(pixels, 0, crop.width(), crop.left, crop.top, crop.width(), crop.height())
        bitmap.recycle()
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        pixels.forEach { pixel ->
            sumR += Color.red(pixel)
            sumG += Color.green(pixel)
            sumB += Color.blue(pixel)
        }
        val count = pixels.size.coerceAtLeast(1)
        return Color.rgb((sumR / count).toInt(), (sumG / count).toInt(), (sumB / count).toInt())
    }

    private fun skyCrop(width: Int, height: Int): Rect {
        val left = (width * 0.0f).toInt()
        val top = (height * 0.05f).toInt()
        val right = (width * 1.0f).toInt().coerceAtLeast(left + 1)
        val bottom = (height * 0.25f).toInt().coerceAtLeast(top + 1)
        return Rect(left, top, right, bottom)
    }

    private fun dayAndMinute(file: File): DayMinute? {
        val match = FILE_PATTERN.matchEntire(file.name) ?: return null
        val day = match.groupValues[1]
        val hour = match.groupValues[2].toInt()
        val minute = match.groupValues[3].toInt()
        return DayMinute(day, hour * 60 + minute)
    }

    companion object {
        private const val TAG = "FenetreDaylight"
        private const val DAYLIGHT_FILE = "daylight.png"
        private const val DAILY_BAND_HEIGHT = 1440
        private const val SKY_SAMPLE_SIZE = 512
        private val DEFAULT_SKY_COLOR = Color.rgb(10, 10, 20)
        private val FILE_PATTERN = Regex("""(\d{4}-\d{2}-\d{2})T(\d{2})-(\d{2})-\d{2}.*\.jpg""")
    }
}

private data class DayMinute(
    val day: String,
    val minute: Int,
)

private class ColorStats {
    private var sumR = 0L
    private var sumG = 0L
    private var sumB = 0L
    private var count = 0

    fun add(color: Int) {
        sumR += Color.red(color)
        sumG += Color.green(color)
        sumB += Color.blue(color)
        count += 1
    }

    fun color(): Int {
        if (count <= 0) {
            return Color.rgb(10, 10, 20)
        }
        return Color.rgb((sumR / count).toInt(), (sumG / count).toInt(), (sumB / count).toInt())
    }
}
