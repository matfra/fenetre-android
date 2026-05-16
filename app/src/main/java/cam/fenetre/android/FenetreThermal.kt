package cam.fenetre.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

object FenetreThermal {
    fun status(context: Context, settings: FenetreCameraSettings): FenetreThermalStatus {
        val batteryTemperatureCelsius = batteryTemperatureCelsius(context)
        val thermalStatus = androidThermalStatus(context)
        val thresholdCelsius = settings.cooldownBatteryTemperatureCelsius()
        val thermalStatusThreshold = settings.cooldownThermalStatusThreshold()
        val enabled = settings.cooldownEnabled()
        val hotBattery = batteryTemperatureCelsius?.let { it >= thresholdCelsius } ?: false
        val severeThermal = thermalStatusThreshold.value > 0 &&
            (thermalStatus?.let { it >= thermalStatusThreshold.value } ?: false)
        return FenetreThermalStatus(
            enabled = enabled,
            paused = enabled && (hotBattery || severeThermal),
            batteryTemperatureCelsius = batteryTemperatureCelsius,
            thresholdCelsius = thresholdCelsius,
            thermalStatusThreshold = thermalStatusThreshold.value,
            androidThermalStatus = thermalStatus,
        )
    }

    fun batteryStatus(context: Context): Pair<Double?, Double?> {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null to null
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val levelPercent = if (level >= 0 && scale > 0) level * 100.0 / scale else null
            val tempTenthsC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            val tempC = if (tempTenthsC != Int.MIN_VALUE) tempTenthsC / 10.0 else null
            levelPercent to tempC
        } catch (_: Exception) {
            null to null
        }
    }

    private fun batteryTemperatureCelsius(context: Context): Double? = batteryStatus(context).second

    private fun androidThermalStatus(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }
        return try {
            context.getSystemService(PowerManager::class.java)?.currentThermalStatus
        } catch (_: Exception) {
            null
        }
    }
}

data class FenetreThermalStatus(
    val enabled: Boolean,
    val paused: Boolean,
    val batteryTemperatureCelsius: Double?,
    val thresholdCelsius: Double,
    val thermalStatusThreshold: Int,
    val androidThermalStatus: Int?,
)
