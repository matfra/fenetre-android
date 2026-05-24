package cam.fenetre.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

class FenetreOutputResize(private val settings: FenetreCameraSettings) {
    fun apply(file: File): Boolean {
        val source = BitmapFactory.decodeFile(file.absolutePath) ?: return false
        val targetSize = parseSize(settings.outputResizeSize())
        if (targetSize == null) {
            source.recycle()
            return false
        }
        val (targetWidth, targetHeight) = targetSize
        if (targetWidth == source.width && targetHeight == source.height) {
            source.recycle()
            return false
        }

        val resized = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        source.recycle()
        return try {
            file.outputStream().use { output ->
                resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            }
            resized.recycle()
            true
        } catch (exception: Exception) {
            resized.recycle()
            Log.w(TAG, "Unable to resize output image", exception)
            false
        }
    }

    private fun parseSize(value: String): Pair<Int, Int>? {
        if (value.isBlank()) {
            return null
        }
        val parts = value.split("x")
        if (parts.size != 2) {
            return null
        }
        val width = parts[0].toIntOrNull()?.coerceAtLeast(1) ?: return null
        val height = parts[1].toIntOrNull()?.coerceAtLeast(1) ?: return null
        return width to height
    }

    companion object {
        private const val JPEG_QUALITY = 92
        private const val TAG = "FenetreOutputResize"
    }
}
