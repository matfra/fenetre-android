package cam.fenetre.android

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

class FenetreSunSchedule(private val settings: FenetreCameraSettings) {
    fun captureIntervalSeconds(now: ZonedDateTime = now()): Int {
        return if (isSunriseSunsetWindow(now)) {
            settings.sunriseSunsetFastIntervalSeconds()
        } else {
            settings.captureIntervalSeconds()
        }
    }

    fun isSunriseSunsetWindow(now: ZonedDateTime = now()): Boolean {
        if (!settings.sunriseSunsetFastEnabled()) {
            return false
        }
        val sunWindow = sunWindowFor(now) ?: return false
        val sunriseStart = sunWindow.sunrise.minusMinutes(settings.sunriseOffsetStartMinutes().toLong())
        val sunriseEnd = sunWindow.sunrise.plusMinutes(settings.sunriseOffsetEndMinutes().toLong())
        val sunsetStart = sunWindow.sunset.minusMinutes(settings.sunsetOffsetStartMinutes().toLong())
        val sunsetEnd = sunWindow.sunset.plusMinutes(settings.sunsetOffsetEndMinutes().toLong())
        return now in sunriseStart..sunriseEnd || now in sunsetStart..sunsetEnd
    }

    fun isNightExposureBoostWindow(now: ZonedDateTime = now()): Boolean {
        if (settings.nightExposureBoostStops() <= 0.0) {
            return false
        }
        val sunWindow = sunWindowFor(now) ?: return false
        val buffer = settings.nightExposureBoostTwilightBufferMinutes().toLong()
        return now < sunWindow.sunrise.minusMinutes(buffer) || now > sunWindow.sunset.plusMinutes(buffer)
    }

    private fun now(): ZonedDateTime = ZonedDateTime.now(zoneIdOrDefault(settings.overlayTimezone()))

    private fun sunWindowFor(now: ZonedDateTime): SunWindowDateTime? {
        val sunWindow = sunriseSunset(
            now.toLocalDate(),
            settings.overlayLatitude(),
            settings.overlayLongitude(),
            now.offset.totalSeconds / 3600.0,
        ) ?: return null
        val sunrise = decimalHourToZonedDateTime(now.toLocalDate(), sunWindow.sunriseHour, now.zone)
        val sunset = decimalHourToZonedDateTime(now.toLocalDate(), sunWindow.sunsetHour, now.zone)
        return SunWindowDateTime(sunrise, sunset)
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
        return SunWindow(sunrise, sunset)
    }

    private fun equationOfTimeMinutes(dayOfYear: Double): Double {
        val b = Math.toRadians((360.0 / 365.0) * (dayOfYear - 81.0))
        return 9.87 * sin(2.0 * b) - 7.53 * cos(b) - 1.5 * sin(b)
    }

    private fun decimalHourToZonedDateTime(date: LocalDate, hour: Double, zone: ZoneId): ZonedDateTime {
        val seconds = (hour.floorMod24() * 3600.0).roundToLong().coerceIn(0L, 86_399L)
        return date.atStartOfDay(zone).plusSeconds(seconds)
    }

    private fun Double.floorMod24(): Double {
        val mod = this % 24.0
        return if (mod < 0) mod + 24.0 else mod
    }

    private fun zoneIdOrDefault(zoneId: String): ZoneId {
        return try {
            ZoneId.of(zoneId)
        } catch (_: Exception) {
            ZoneId.of("UTC")
        }
    }

    private data class SunWindow(
        val sunriseHour: Double,
        val sunsetHour: Double,
    )

    private data class SunWindowDateTime(
        val sunrise: ZonedDateTime,
        val sunset: ZonedDateTime,
    )
}
