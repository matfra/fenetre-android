package cam.fenetre.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import java.io.File
import kotlin.math.pow

class FenetreSsim(private val settings: FenetreCameraSettings) {
    fun sample(file: File): SsimSample? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        val crop = cropRect(bounds.outWidth, bounds.outHeight) ?: return null
        val options = BitmapFactory.Options()
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val scaled = Bitmap.createBitmap(SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(scaled)
        canvas.drawBitmap(decoded, crop, Rect(0, 0, SAMPLE_SIZE, SAMPLE_SIZE), null)
        decoded.recycle()

        val luma = DoubleArray(SAMPLE_SIZE * SAMPLE_SIZE)
        var index = 0
        for (y in 0 until SAMPLE_SIZE) {
            for (x in 0 until SAMPLE_SIZE) {
                val pixel = scaled.getPixel(x, y)
                luma[index++] = 0.2126 * Color.red(pixel) +
                    0.7152 * Color.green(pixel) +
                    0.0722 * Color.blue(pixel)
            }
        }
        scaled.recycle()
        return SsimSample(luma)
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
        private const val SAMPLE_SIZE = 50
        private const val TAG = "FenetreSsim"
    }
}

data class SsimSample(val luma: DoubleArray)

data class SsimResult(
    val value: Double?,
    val target: Double,
    val intervalSeconds: Int,
    val compared: Boolean,
)
