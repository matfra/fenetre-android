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
        val batteryPower = batteryPowerStatus(context)
        val thermalStatus = androidThermalStatus(context)
        val thresholdCelsius = settings.cooldownBatteryTemperatureCelsius()
        val thermalStatusThreshold = settings.cooldownThermalStatusThreshold()
        val enabled = settings.cooldownEnabled()
        val hotBattery = batteryTemperatureCelsius?.let { it >= thresholdCelsius } ?: false
        val severeThermal = thermalStatusThreshold.value > 0 &&
            (thermalStatus?.let { it >= thermalStatusThreshold.value } ?: false)
        val lowBattery = settings.lowBatteryPauseEnabled() &&
            batteryPower.levelPercent?.let { it < settings.lowBatteryPauseThresholdPercent() } == true &&
            batteryPower.charging == false
        return FenetreThermalStatus(
            enabled = enabled || settings.lowBatteryPauseEnabled(),
            paused = (enabled && (hotBattery || severeThermal)) || lowBattery,
            thermalPaused = enabled && (hotBattery || severeThermal),
            lowBatteryPaused = lowBattery,
            batteryTemperatureCelsius = batteryTemperatureCelsius,
            batteryLevelPercent = batteryPower.levelPercent,
            batteryCharging = batteryPower.charging,
            lowBatteryThresholdPercent = settings.lowBatteryPauseThresholdPercent(),
            lowBatteryProtectionEnabled = settings.lowBatteryPauseEnabled(),
            thresholdCelsius = thresholdCelsius,
            thermalStatusThreshold = thermalStatusThreshold.value,
            androidThermalStatus = thermalStatus,
        )
    }

    fun batteryStatus(context: Context): Pair<Double?, Double?> {
        val power = batteryPowerStatus(context)
        return power.levelPercent to power.temperatureCelsius
    }

    fun batteryPowerStatus(context: Context): FenetreBatteryPowerStatus {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return FenetreBatteryPowerStatus(null, null, null)
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val levelPercent = if (level >= 0 && scale > 0) level * 100.0 / scale else null
            val tempTenthsC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            val tempC = if (tempTenthsC != Int.MIN_VALUE) tempTenthsC / 10.0 else null
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugged != 0
            FenetreBatteryPowerStatus(levelPercent, tempC, charging)
        } catch (_: Exception) {
            FenetreBatteryPowerStatus(null, null, null)
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
    val thermalPaused: Boolean,
    val lowBatteryPaused: Boolean,
    val batteryTemperatureCelsius: Double?,
    val batteryLevelPercent: Double?,
    val batteryCharging: Boolean?,
    val lowBatteryThresholdPercent: Int,
    val lowBatteryProtectionEnabled: Boolean,
    val thresholdCelsius: Double,
    val thermalStatusThreshold: Int,
    val androidThermalStatus: Int?,
)

data class FenetreBatteryPowerStatus(
    val levelPercent: Double?,
    val temperatureCelsius: Double?,
    val charging: Boolean?,
)
