package cam.fenetre.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import java.io.File

class FenetreOutputResize(private val settings: FenetreCameraSettings) {
    fun apply(file: File): Boolean {
        val source = BitmapFactory.decodeFile(file.absolutePath) ?: return false
        val targetSize = parseSize(settings.outputResizeSize())
        val cropMode = settings.outputCropMode()
        if (targetSize == null && cropMode == OutputCropMode.NONE) {
            source.recycle()
            return false
        }

        val cropped = cropIfNeeded(source, cropMode)
        val resized = resizeIfNeeded(cropped, targetSize)
        if (resized === source) {
            source.recycle()
            return false
        }
        return try {
            file.outputStream().use { output ->
                resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            }
            resized.recycle()
            true
        } catch (exception: Exception) {
            resized.recycle()
            Log.w(TAG, "Unable to post-process output image", exception)
            false
        }
    }

    private fun resizeIfNeeded(source: Bitmap, targetSize: Pair<Int, Int>?): Bitmap {
        if (targetSize == null) {
            return source
        }
        val (targetWidth, targetHeight) = targetSize
        if (targetWidth == source.width && targetHeight == source.height) {
            return source
        }
        val resized = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        source.recycle()
        return resized
    }

    private fun cropIfNeeded(source: Bitmap, mode: OutputCropMode): Bitmap {
        val crop = when (mode) {
            OutputCropMode.NONE -> null
            OutputCropMode.TOP_16_9 -> topAspectCrop(source.width, source.height, 16.0 / 9.0)
            OutputCropMode.CUSTOM_RECT -> customCrop(source.width, source.height, settings.outputCropRect())
        } ?: return source
        if (crop.width() == source.width && crop.height() == source.height) {
            return source
        }
        val cropped = Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height())
        source.recycle()
        return cropped
    }

    private fun topAspectCrop(width: Int, height: Int, aspectRatio: Double): Rect? {
        if (width <= 0 || height <= 0) {
            return null
        }
        val targetHeight = (width / aspectRatio).toInt().coerceAtLeast(1)
        if (targetHeight >= height) {
            return null
        }
        return Rect(0, 0, width, targetHeight)
    }

    private fun customCrop(width: Int, height: Int, value: String): Rect? {
        val parts = value.split(",").map { it.trim().toIntOrNull() ?: return null }
        if (parts.size != 4) {
            return null
        }
        val left = parts[0].coerceIn(0, width)
        val top = parts[1].coerceIn(0, height)
        val right = parts[2].coerceIn(0, width)
        val bottom = parts[3].coerceIn(0, height)
        if (right <= left || bottom <= top) {
            return null
        }
        return Rect(left, top, right, bottom)
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
