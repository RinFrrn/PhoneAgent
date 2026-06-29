package com.mobileagent.phoneagent.harness.runtime

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build

data class RuntimeDeviceSnapshot(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val screenResolution: String,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null
) {
    fun deviceLabel(): String {
        val maker = manufacturer.trim()
        val modelName = model.trim()
        return when {
            maker.isBlank() && modelName.isBlank() -> "未知设备"
            maker.isBlank() -> modelName
            modelName.isBlank() -> maker
            modelName.startsWith(maker, ignoreCase = true) -> modelName
            else -> "$maker $modelName"
        }
    }

    fun batteryText(): String {
        val percent = batteryPercent?.let { "$it%" } ?: "未知"
        val chargingText = when (charging) {
            true -> "充电中"
            false -> "未充电"
            null -> "充电状态未知"
        }
        return "$percent · $chargingText"
    }

    fun toDisplayText(): String {
        return "${deviceLabel()} · Android $androidVersion (SDK $sdkInt) · $screenResolution · 电量 ${batteryText()}"
    }

    fun lowBatteryWarning(): String? {
        val percent = batteryPercent ?: return null
        if (charging == true) {
            return null
        }
        return if (percent <= LOW_BATTERY_PERCENT) {
            "设备电量 $percent%，长任务前建议充电。"
        } else {
            null
        }
    }

    private companion object {
        const val LOW_BATTERY_PERCENT = 20
    }
}

object RuntimeDeviceSnapshotReader {
    fun read(context: Context): RuntimeDeviceSnapshot {
        val metrics = context.resources.displayMetrics
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return RuntimeDeviceSnapshot(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            screenResolution = "${metrics.widthPixels}x${metrics.heightPixels}",
            batteryPercent = readBatteryPercent(batteryIntent),
            charging = readChargingState(batteryIntent)
        )
    }

    private fun readBatteryPercent(intent: Intent?): Int? {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: return null
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            return null
        }
        return ((level * 100f) / scale).toInt().coerceIn(0, 100)
    }

    private fun readChargingState(intent: Intent?): Boolean? {
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: return null
        return when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING,
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }
    }
}
