package cam.fenetre.android

import android.media.ExifInterface
import java.io.File
import java.util.Locale

data class CaptureExif(
    val iso: Int?,
    val exposureTimeSeconds: Double?,
    val shutterSpeed: String?,
    val whiteBalance: String?,
) {
    fun exposureComposite(): Double? {
        val sensitivity = iso ?: return null
        val exposure = exposureTimeSeconds ?: return null
        return sensitivity * exposure
    }

    companion object {
        fun fromFile(file: File): CaptureExif {
            val exif = ExifInterface(file.absolutePath)
            val exposure = exif.getAttributeDoubleOrNull(ExifInterface.TAG_EXPOSURE_TIME)
            return CaptureExif(
                iso = exif.getAttributeIntOrNull(ExifInterface.TAG_ISO_SPEED_RATINGS),
                exposureTimeSeconds = exposure,
                shutterSpeed = formatShutterSpeed(exposure),
                whiteBalance = when (exif.getAttributeIntOrNull(ExifInterface.TAG_WHITE_BALANCE)) {
                    ExifInterface.WHITEBALANCE_AUTO -> "auto"
                    ExifInterface.WHITEBALANCE_MANUAL -> "manual"
                    else -> null
                },
            )
        }

        private fun formatShutterSpeed(exposureTimeSeconds: Double?): String? {
            val exposure = exposureTimeSeconds ?: return null
            if (exposure <= 0.0) {
                return null
            }
            return if (exposure >= 1.0) {
                String.format(Locale.US, "%.1fs", exposure).trimEnd('0').trimEnd('.')
            } else {
                "1/${Math.round(1.0 / exposure)}"
            }
        }
    }
}

private fun ExifInterface.getAttributeIntOrNull(tag: String): Int? {
    val value = getAttribute(tag) ?: return null
    return value.toIntOrNull()
}

private fun ExifInterface.getAttributeDoubleOrNull(tag: String): Double? {
    val value = getAttribute(tag) ?: return null
    return value.toDoubleOrNull()
}
