package cam.fenetre.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

class FenetreOverlays(private val settings: FenetreCameraSettings) {
    fun apply(file: File, capturedAt: ZonedDateTime = ZonedDateTime.now()): Boolean {
        if (!settings.timestampOverlayEnabled() && !settings.sunPathOverlayEnabled()) {
            return false
        }
        val source = BitmapFactory.decodeFile(file.absolutePath) ?: return false
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        source.recycle()
        val canvas = Canvas(bitmap)
        val sunPathBounds = if (settings.sunPathOverlayEnabled()) {
            drawSunPath(canvas, bitmap.width, bitmap.height, capturedAt)
        } else {
            null
        }
        if (settings.timestampOverlayEnabled()) {
            drawTimestamp(canvas, bitmap.width, bitmap.height, capturedAt, sunPathBounds, settings.sunPathOverlayPosition())
        }
        FileOutputStream(file).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        }
        bitmap.recycle()
        return true
    }

    private fun drawTimestamp(
        canvas: Canvas,
        width: Int,
        height: Int,
        capturedAt: ZonedDateTime,
        reservedBottomBounds: RectF?,
        sunPathPosition: SunPathOverlayPosition,
    ) {
        val timestamp = capturedAt
            .withZoneSameInstant(zoneIdOrDefault(settings.overlayTimezone()))
            .format(TIMESTAMP_FORMATTER)
        val textSize = max(24f, width * 0.018f)
        val padding = max(10f, width * 0.008f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            setShadowLayer(4f, 0f, 1f, Color.argb(190, 0, 0, 0))
        }
        val bounds = android.graphics.Rect()
        paint.getTextBounds(timestamp, 0, timestamp.length, bounds)
        val position = settings.timestampOverlayPosition()
        val reservedInset = reservedBottomBounds?.let {
            if (sunPathPosition == SunPathOverlayPosition.BOTTOM && position.isBottom()) {
                height - it.top + padding
            } else if (sunPathPosition == SunPathOverlayPosition.TOP && position.isTop()) {
                it.bottom + padding
            } else {
                0f
            }
        } ?: 0f
        val boxWidth = bounds.width() + padding * 2f
        val boxHeight = bounds.height() + padding * 2f
        val boxLeft = if (position.isLeft()) padding else width - boxWidth - padding
        val boxTop = if (position.isTop()) padding + reservedInset else height - reservedInset - boxHeight - padding
        val boxRight = boxLeft + boxWidth
        val boxBottom = boxTop + boxHeight
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 35, 39, 47)
        }
        canvas.drawRoundRect(RectF(boxLeft, boxTop, boxRight, boxBottom), 8f, 8f, bgPaint)
        canvas.drawText(timestamp, boxLeft + padding, boxBottom - padding - bounds.bottom, paint)
    }

    private fun drawSunPath(canvas: Canvas, width: Int, height: Int, capturedAt: ZonedDateTime): RectF {
        val zoneId = zoneIdOrDefault(settings.overlayTimezone())
        val localTime = capturedAt.withZoneSameInstant(zoneId).toLocalTime()
        val sunWindow = sunriseSunset(
            capturedAt.withZoneSameInstant(zoneId).toLocalDate(),
            settings.overlayLatitude(),
            settings.overlayLongitude(),
            capturedAt.withZoneSameInstant(zoneId).offset.totalSeconds / 3600.0,
        )

        val bounds = sunPathBounds(width, height)
        val overlayWidth = bounds.width()
        val overlayHeight = bounds.height()
        val left = bounds.left
        val top = bounds.top
        val bottom = bounds.bottom
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(95, 15, 18, 24)
        }
        canvas.drawRoundRect(bounds, 8f, 8f, bgPaint)

        val minorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(125, 210, 215, 225)
            strokeWidth = max(1f, overlayWidth / 1000f)
        }
        val majorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 230, 235, 245)
            strokeWidth = max(2f, overlayWidth / 420f)
        }
        for (hour in 0..23) {
            val x = left + overlayWidth * hour / 24f
            val isMajor = hour == 6 || hour == 12 || hour == 18
            val lineTop = if (isMajor) top else top + overlayHeight * 0.50f
            canvas.drawLine(x, lineTop, x, bottom, if (isMajor) majorPaint else minorPaint)
        }

        if (sunWindow != null) {
            val sunriseX = left + overlayWidth * (sunWindow.sunriseHour / 24.0).toFloat()
            val sunsetX = left + overlayWidth * (sunWindow.sunsetHour / 24.0).toFloat()
            if (sunsetX > sunriseX) {
                val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(175, 255, 181, 64)
                    style = Paint.Style.STROKE
                    strokeWidth = max(4f, overlayWidth / 180f)
                }
                val strokeInset = arcPaint.strokeWidth / 2f
                val baseline = bottom - max(5f, strokeInset + 2f)
                val maxArcHeight = (baseline - top - strokeInset).coerceAtLeast(0f)
                val arcHeight = maxArcHeight * sunWindow.maxElevationRatio.toFloat()
                val arcRect = RectF(sunriseX, baseline - arcHeight, sunsetX, baseline + arcHeight)
                canvas.drawArc(arcRect, 180f, 180f, false, arcPaint)
            }
        }

        val timeX = left + overlayWidth * secondsOfDay(localTime) / 86400f
        val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = max(3f, overlayWidth / 250f)
        }
        val markerHalfWidth = max(4f, overlayWidth / 170f)
        canvas.drawRoundRect(
            RectF(timeX - markerHalfWidth, top + 2f, timeX + markerHalfWidth, bottom - 2f),
            3f,
            3f,
            markerPaint,
        )
        return bounds
    }

    private fun sunPathBounds(width: Int, height: Int): RectF {
        val overlayWidth = min(width * 0.92f, 1000f)
        val overlayHeight = max(52f, overlayWidth * 0.06f)
        val left = (width - overlayWidth) / 2f
        val margin = max(12f, height * 0.012f)
        val bottom = if (settings.sunPathOverlayPosition() == SunPathOverlayPosition.TOP) {
            margin + overlayHeight
        } else {
            height - margin
        }
        val top = bottom - overlayHeight
        return RectF(left, top, left + overlayWidth, bottom)
    }

    private fun TimestampOverlayPosition.isLeft(): Boolean {
        return this == TimestampOverlayPosition.TOP_LEFT || this == TimestampOverlayPosition.BOTTOM_LEFT
    }

    private fun TimestampOverlayPosition.isTop(): Boolean {
        return this == TimestampOverlayPosition.TOP_LEFT || this == TimestampOverlayPosition.TOP_RIGHT
    }

    private fun TimestampOverlayPosition.isBottom(): Boolean {
        return !isTop()
    }

    private fun sunriseSunset(date: LocalDate, latitude: Double, longitude: Double, utcOffsetHours: Double): SunWindow? {
        val dayOfYear = date.dayOfYear.toDouble()
        val declination = Math.toRadians(23.44) * sin(Math.toRadians((360.0 / 365.0) * (dayOfYear - 81.0)))
        val latitudeRad = Math.toRadians(latitude)
        val cosHourAngle = (-tan(latitudeRad) * tan(declination)).coerceIn(-1.0, 1.0)
        val hourAngle = Math.toDegrees(acos(cosHourAngle))
        val daylightHours = 2.0 * hourAngle / 15.0
        if (daylightHours <= 0.0) {
            return null
        }
        val equationOfTime = equationOfTimeMinutes(dayOfYear)
        val localStandardMeridian = utcOffsetHours * 15.0
        val solarNoon = 12.0 + (localStandardMeridian - longitude) / 15.0 - equationOfTime / 60.0
        val sunrise = (solarNoon - daylightHours / 2.0).floorMod24()
        val sunset = (solarNoon + daylightHours / 2.0).floorMod24()
        val maxElevation = 90.0 - kotlin.math.abs(latitude - Math.toDegrees(declination))
        return SunWindow(sunrise, sunset, (maxElevation / 90.0).coerceIn(0.0, 1.0))
    }

    private fun equationOfTimeMinutes(dayOfYear: Double): Double {
        val b = Math.toRadians((360.0 / 365.0) * (dayOfYear - 81.0))
        return 9.87 * sin(2.0 * b) - 7.53 * cos(b) - 1.5 * sin(b)
    }

    private fun secondsOfDay(time: LocalTime): Float {
        return (time.hour * 3600 + time.minute * 60 + time.second).toFloat()
    }

    private fun Double.floorMod24(): Double {
        val mod = this % 24.0
        return if (mod < 0) mod + 24.0 else mod
    }

    private fun zoneIdOrDefault(zoneId: String): java.time.ZoneId {
        return try {
            java.time.ZoneId.of(zoneId)
        } catch (_: Exception) {
            java.time.ZoneId.of("UTC")
        }
    }

    private data class SunWindow(
        val sunriseHour: Double,
        val sunsetHour: Double,
        val maxElevationRatio: Double,
    )

    companion object {
        private const val JPEG_QUALITY = 92
        private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
    }
}
