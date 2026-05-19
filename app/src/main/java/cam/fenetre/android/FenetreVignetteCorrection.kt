package cam.fenetre.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import java.io.File
import kotlin.math.hypot
import kotlin.math.pow

class FenetreVignetteCorrection(private val settings: FenetreCameraSettings) {
    fun apply(file: File): Boolean {
        if (!settings.vignetteCorrectionEnabled()) {
            return false
        }
        val strength = settings.vignetteCorrectionStrength()
        if (strength <= 0.0) {
            return false
        }
        val source = BitmapFactory.decodeFile(file.absolutePath) ?: return false
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        if (bitmap !== source) {
            source.recycle()
        }
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 1 || height <= 1) {
            bitmap.recycle()
            return false
        }

        val centerX = (width - 1) / 2.0
        val centerY = (height - 1) / 2.0
        val maxRadius = hypot(centerX, centerY).coerceAtLeast(1.0)
        val power = settings.vignetteCorrectionPower()
        val correctionRadius = settings.vignetteCorrectionRadius()
        val pixels = IntArray(width)
        for (y in 0 until height) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
            val normalizedY = y - centerY
            for (x in 0 until width) {
                val radius = (hypot(x - centerX, normalizedY) / maxRadius / correctionRadius).coerceAtMost(1.0)
                val gain = (1.0 + strength * radius.pow(power)) / (1.0 + strength)
                val pixel = pixels[x]
                pixels[x] = Color.rgb(
                    applyGain(Color.red(pixel), gain),
                    applyGain(Color.green(pixel), gain),
                    applyGain(Color.blue(pixel), gain),
                )
            }
            bitmap.setPixels(pixels, 0, width, 0, y, width, 1)
        }

        return try {
            file.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            }
            bitmap.recycle()
            true
        } catch (exception: Exception) {
            bitmap.recycle()
            Log.w(TAG, "Unable to apply vignette correction", exception)
            false
        }
    }

    private fun applyGain(value: Int, gain: Double): Int {
        return (value * gain).toInt().coerceIn(0, 255)
    }

    companion object {
        private const val JPEG_QUALITY = 92
        private const val TAG = "FenetreVignetteCorrection"
    }
}
