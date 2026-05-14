package cam.fenetre.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

object JpegOrientation {
    fun normalize(file: File, manualRotationDegrees: Int) {
        val exif = ExifInterface(file.absolutePath)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val transform = matrixFor(orientation) ?: Matrix()
        if (manualRotationDegrees != 0) {
            transform.postRotate(manualRotationDegrees.toFloat())
        }

        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
        FileOutputStream(file).use { output ->
            rotated.compress(Bitmap.CompressFormat.JPEG, 92, output)
        }
        bitmap.recycle()
        if (rotated != bitmap) {
            rotated.recycle()
        }

        stripOrientation(file)
    }

    private fun stripOrientation(file: File) {
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            setAttribute(ExifInterface.TAG_IMAGE_WIDTH, null)
            setAttribute(ExifInterface.TAG_IMAGE_LENGTH, null)
            saveAttributes()
        }
    }

    private fun matrixFor(orientation: Int): Matrix? {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return null
        }
        return matrix
    }
}
