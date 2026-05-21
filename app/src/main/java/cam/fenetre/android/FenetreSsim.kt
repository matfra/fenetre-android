package cam.fenetre.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import java.io.File
import java.util.ArrayDeque
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class FenetreSsim(private val settings: FenetreCameraSettings) {
    fun analyze(file: File): SsimAnalysis? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        val crop = cropRect(bounds.outWidth, bounds.outHeight) ?: return null
        val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val starDetection = countStars(decoded, crop)
        val scaled = Bitmap.createBitmap(SSIM_SAMPLE_SIZE, SSIM_SAMPLE_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(scaled).drawBitmap(decoded, crop, Rect(0, 0, SSIM_SAMPLE_SIZE, SSIM_SAMPLE_SIZE), null)
        decoded.recycle()

        val luma = DoubleArray(SSIM_SAMPLE_SIZE * SSIM_SAMPLE_SIZE)
        var index = 0
        for (y in 0 until SSIM_SAMPLE_SIZE) {
            for (x in 0 until SSIM_SAMPLE_SIZE) {
                val pixel = scaled.getPixel(x, y)
                luma[index++] = 0.2126 * Color.red(pixel) +
                    0.7152 * Color.green(pixel) +
                    0.0722 * Color.blue(pixel)
            }
        }
        scaled.recycle()
        return SsimAnalysis(SsimSample(luma), starDetection)
    }

    fun compare(previous: SsimSample, current: SsimSample): Double {
        val x = previous.luma
        val y = current.luma
        if (x.size != y.size || x.isEmpty()) {
            return 1.0
        }
        val n = x.size
        val meanX = x.sum() / n
        val meanY = y.sum() / n
        var varianceX = 0.0
        var varianceY = 0.0
        var covariance = 0.0
        for (i in x.indices) {
            val dx = x[i] - meanX
            val dy = y[i] - meanY
            varianceX += dx * dx
            varianceY += dy * dy
            covariance += dx * dy
        }
        val denominator = (n - 1).coerceAtLeast(1).toDouble()
        varianceX /= denominator
        varianceY /= denominator
        covariance /= denominator
        val c1 = (0.01 * 255.0).pow(2)
        val c2 = (0.03 * 255.0).pow(2)
        return ((2 * meanX * meanY + c1) * (2 * covariance + c2)) /
            ((meanX * meanX + meanY * meanY + c1) * (varianceX + varianceY + c2))
    }

    private fun countStars(decoded: Bitmap, crop: Rect): StarDetectionResult {
        val scale = min(
            MAX_STAR_ANALYSIS_SIZE.toDouble() / crop.width().toDouble(),
            MAX_STAR_ANALYSIS_SIZE.toDouble() / crop.height().toDouble(),
        ).coerceAtMost(1.0)
        val width = (crop.width() * scale).roundToInt().coerceAtLeast(1)
        val height = (crop.height() * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawBitmap(decoded, crop, Rect(0, 0, width, height), null)

        val luma = IntArray(width * height)
        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                luma[index++] = (
                    0.2126 * Color.red(pixel) +
                        0.7152 * Color.green(pixel) +
                        0.0722 * Color.blue(pixel)
                    ).roundToInt()
            }
        }
        bitmap.recycle()

        val sorted = luma.copyOf()
        sorted.sort()
        val background = sorted[sorted.size / 2]
        val threshold = (background + settings.starDetectionThresholdLuma()).coerceAtMost(255)
        val visited = BooleanArray(luma.size)
        val queue = ArrayDeque<Int>()
        var starCount = 0
        for (start in luma.indices) {
            if (visited[start] || luma[start] < threshold) {
                continue
            }
            visited[start] = true
            queue.clear()
            queue.add(start)
            var area = 0
            var touchesEdge = false
            while (!queue.isEmpty()) {
                val point = queue.removeFirst()
                area += 1
                val x = point % width
                val y = point / width
                touchesEdge = touchesEdge || x == 0 || y == 0 || x == width - 1 || y == height - 1
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) {
                            continue
                        }
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until width || ny !in 0 until height) {
                            continue
                        }
                        val neighbor = ny * width + nx
                        if (!visited[neighbor] && luma[neighbor] >= threshold) {
                            visited[neighbor] = true
                            queue.add(neighbor)
                        }
                    }
                }
            }
            if (!touchesEdge && area in 1..settings.starDetectionMaxBlobPixels()) {
                starCount += 1
            }
        }
        return StarDetectionResult(
            count = starCount,
            detected = starCount >= settings.starDetectionMinCount(),
            thresholdLuma = threshold,
            backgroundLuma = background,
        )
    }

    private fun cropRect(width: Int, height: Int): Rect? {
        val values = settings.ssimArea().split(",").mapNotNull { it.trim().toDoubleOrNull() }
        if (values.size != 4) {
            Log.w(TAG, "Invalid SSIM area ${settings.ssimArea()}; using full image")
            return Rect(0, 0, width, height)
        }
        val ratioCoordinates = values.all { it <= 1.0 }
        val left = if (ratioCoordinates) (width * values[0]).toInt() else values[0].toInt()
        val top = if (ratioCoordinates) (height * values[1]).toInt() else values[1].toInt()
        val right = if (ratioCoordinates) (width * values[2]).toInt() else values[2].toInt()
        val bottom = if (ratioCoordinates) (height * values[3]).toInt() else values[3].toInt()
        val crop = Rect(
            left.coerceIn(0, width),
            top.coerceIn(0, height),
            right.coerceIn(0, width),
            bottom.coerceIn(0, height),
        )
        if (crop.width() <= 0 || crop.height() <= 0) {
            Log.w(TAG, "Invalid SSIM crop ${settings.ssimArea()} for image ${width}x$height")
            return null
        }
        return crop
    }

    companion object {
        private const val SSIM_SAMPLE_SIZE = 50
        private const val MAX_STAR_ANALYSIS_SIZE = 500
        private const val TAG = "FenetreSsim"
    }
}

data class SsimAnalysis(
    val sample: SsimSample,
    val starDetection: StarDetectionResult,
)

data class SsimSample(val luma: DoubleArray)

data class SsimResult(
    val value: Double?,
    val target: Double,
    val intervalSeconds: Int,
    val compared: Boolean,
    val starsDetected: Boolean,
    val starCount: Int,
    val suppressedByStars: Boolean,
    val starThresholdLuma: Int?,
    val starBackgroundLuma: Int?,
)

data class StarDetectionResult(
    val count: Int,
    val detected: Boolean,
    val thresholdLuma: Int,
    val backgroundLuma: Int,
)
