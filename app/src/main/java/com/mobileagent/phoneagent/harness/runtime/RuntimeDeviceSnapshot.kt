package com.mobileagent.phoneagent.harness.runtime

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.KeyguardManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

data class RuntimeDeviceSnapshot(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val screenResolution: String,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val interactive: Boolean? = null,
    val keyguardLocked: Boolean? = null,
    val powerSaveMode: Boolean? = null
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
        return "${deviceLabel()} · Android $androidVersion (SDK $sdkInt) · $screenResolution · 电量 ${batteryText()} · ${screenStateText()}"
    }

    fun screenStateText(): String {
        val interactiveText = when (interactive) {
            true -> "屏幕亮起"
            false -> "屏幕关闭"
            null -> "屏幕状态未知"
        }
        val lockText = when (keyguardLocked) {
            true -> "已锁屏"
            false -> "未锁屏"
            null -> "锁屏状态未知"
        }
        val powerText = when (powerSaveMode) {
            true -> "省电模式"
            false -> "非省电模式"
            null -> "省电状态未知"
        }
        return "$interactiveText · $lockText · $powerText"
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

    fun blockingWarnings(): List<String> {
        return buildList {
            if (interactive == false) {
                add("设备屏幕关闭，任务执行前请点亮屏幕。")
            }
            if (keyguardLocked == true) {
                add("设备处于锁屏状态，任务执行前请先解锁。")
            }
        }
    }

    fun advisoryWarnings(): List<String> {
        return buildList {
            lowBatteryWarning()?.let(::add)
            if (powerSaveMode == true) {
                add("设备处于省电模式，后台执行和网络请求可能不稳定。")
            }
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
            charging = readChargingState(batteryIntent),
            interactive = readInteractiveState(context),
            keyguardLocked = readKeyguardLocked(context),
            powerSaveMode = readPowerSaveMode(context)
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

    private fun readInteractiveState(context: Context): Boolean? {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return null
        return powerManager.isInteractive
    }

    private fun readKeyguardLocked(context: Context): Boolean? {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            ?: return null
        return keyguardManager.isKeyguardLocked
    }

    private fun readPowerSaveMode(context: Context): Boolean? {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return null
        return powerManager.isPowerSaveMode
    }
}
