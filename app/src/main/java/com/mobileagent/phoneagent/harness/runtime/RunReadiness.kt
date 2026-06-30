package com.mobileagent.phoneagent.harness.runtime

import com.mobileagent.phoneagent.agent.Mode

enum class ReadinessSeverity {
    BLOCKER,
    WARNING,
    INFO
}

data class ReadinessCheck(
    val id: String,
    val severity: ReadinessSeverity,
    val title: String,
    val detail: String
)

data class RunReadinessReport(
    val checks: List<ReadinessCheck>
) {
    val blockers: List<ReadinessCheck>
        get() = checks.filter { it.severity == ReadinessSeverity.BLOCKER }

    val warnings: List<ReadinessCheck>
        get() = checks.filter { it.severity == ReadinessSeverity.WARNING }

    val infos: List<ReadinessCheck>
        get() = checks.filter { it.severity == ReadinessSeverity.INFO }

    val ready: Boolean
        get() = blockers.isEmpty()

    fun statusTitle(running: Boolean): String {
        return when {
            running -> "任务执行中"
            blockers.isNotEmpty() -> "需要完成运行前检查"
            warnings.isNotEmpty() -> "基本就绪"
            else -> "已就绪"
        }
    }

    fun statusDetail(running: Boolean): String {
        return when {
            running -> "可以回到手机继续操作，或在这里停止任务。"
            blockers.isNotEmpty() -> blockers.joinToString("；") { it.title }
            warnings.isNotEmpty() -> warnings.joinToString("；") { it.detail }
            else -> infos.firstOrNull()?.detail ?: "输入任务描述后即可开始。"
        }
    }
}

object RunReadinessChecker {
    fun evaluate(
        modelConfigured: Boolean,
        accessibilityEnabled: Boolean,
        overlayEnabled: Boolean,
        notificationEnabled: Boolean,
        mode: Mode,
        screenCaptureReady: Boolean,
        humanizationEnabled: Boolean,
        deviceSnapshot: RuntimeDeviceSnapshot? = null
    ): RunReadinessReport {
        val checks = mutableListOf<ReadinessCheck>()

        if (!modelConfigured) {
            checks.add(
                ReadinessCheck(
                    id = "model",
                    severity = ReadinessSeverity.BLOCKER,
                    title = "模型未配置",
                    detail = "请先在设置页填写可用模型地址、模型名称和必要的 API Key。"
                )
            )
        }

        if (!accessibilityEnabled) {
            checks.add(
                ReadinessCheck(
                    id = "accessibility",
                    severity = ReadinessSeverity.BLOCKER,
                    title = "无障碍未开启",
                    detail = "需要无障碍服务读取界面并执行点击、滑动、输入。"
                )
            )
        }

        if (!overlayEnabled) {
            checks.add(
                ReadinessCheck(
                    id = "overlay",
                    severity = ReadinessSeverity.BLOCKER,
                    title = "悬浮窗未授权",
                    detail = "需要悬浮窗在后台任务中显示状态和等待用户处理。"
                )
            )
        }

        if (!notificationEnabled) {
            checks.add(
                ReadinessCheck(
                    id = "notification",
                    severity = ReadinessSeverity.BLOCKER,
                    title = "通知权限未授权",
                    detail = "需要通知权限显示前台服务状态。"
                )
            )
        }

        val needsScreenCapture = mode == Mode.VISION || mode == Mode.HYBRID
        if (needsScreenCapture && !screenCaptureReady) {
            checks.add(
                ReadinessCheck(
                    id = "screen_capture",
                    severity = ReadinessSeverity.WARNING,
                    title = "屏幕录制待授权",
                    detail = "当前模式会在开始任务时请求一次屏幕录制权限。"
                )
            )
        }

        deviceSnapshot?.blockingWarnings().orEmpty().forEachIndexed { index, warning ->
            checks.add(
                ReadinessCheck(
                    id = "device_blocker_${index + 1}",
                    severity = ReadinessSeverity.BLOCKER,
                    title = "设备状态不可执行",
                    detail = warning
                )
            )
        }

        deviceSnapshot?.advisoryWarnings().orEmpty().forEachIndexed { index, warning ->
            checks.add(
                ReadinessCheck(
                    id = "device_warning_${index + 1}",
                    severity = ReadinessSeverity.WARNING,
                    title = "设备健康需关注",
                    detail = warning
                )
            )
        }

        if (humanizationEnabled) {
            checks.add(
                ReadinessCheck(
                    id = "humanization",
                    severity = ReadinessSeverity.INFO,
                    title = "执行拟真已启用",
                    detail = "点击、滑动或输入前会应用设置页中的人性化执行策略。"
                )
            )
        }

        if (checks.isEmpty()) {
            checks.add(
                ReadinessCheck(
                    id = "ready",
                    severity = ReadinessSeverity.INFO,
                    title = "运行环境就绪",
                    detail = "输入任务描述后即可开始。"
                )
            )
        }

        return RunReadinessReport(checks)
    }
}
