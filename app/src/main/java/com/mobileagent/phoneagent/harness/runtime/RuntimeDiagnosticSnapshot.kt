package com.mobileagent.phoneagent.harness.runtime

import com.mobileagent.phoneagent.agent.Mode
import com.mobileagent.phoneagent.harness.act.ExecutionHumanizationProfile
import com.mobileagent.phoneagent.harness.trace.RecentTaskPerformanceSummary
import com.mobileagent.phoneagent.harness.trace.TaskHistoryEntry
import com.mobileagent.phoneagent.harness.trace.TaskHistoryStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RuntimeDiagnosticLevel {
    HEALTHY,
    DEGRADED,
    BLOCKED,
    RUNNING
}

data class RuntimeDiagnosticSnapshot(
    val generatedAt: Long,
    val level: RuntimeDiagnosticLevel,
    val running: Boolean,
    val mode: Mode,
    val modelLabel: String,
    val readiness: RunReadinessReport,
    val humanizationProfile: ExecutionHumanizationProfile,
    val recentSummary: RecentTaskPerformanceSummary,
    val latestHistory: TaskHistoryEntry?,
    val deviceSnapshot: RuntimeDeviceSnapshot? = null
) {
    fun compactText(): String {
        val readinessText = when {
            readiness.blockers.isNotEmpty() -> "阻塞 ${readiness.blockers.size}"
            readiness.warnings.isNotEmpty() -> "警告 ${readiness.warnings.size}"
            else -> "就绪"
        }
        val recentText = when {
            recentSummary.isEmpty() -> "暂无历史"
            recentSummary.finishedCount > 0 -> "成功 ${recentSummary.successCount}/${recentSummary.finishedCount}"
            else -> "运行中 ${recentSummary.runningCount}"
        }
        val modelText = if (recentSummary.modelCallSummary.isEmpty()) {
            "模型未统计"
        } else {
            "模型均耗 ${recentSummary.modelCallSummary.averageLatencyMs}ms"
        }
        val deviceText = deviceSnapshot?.lowBatteryWarning()?.let { " · 低电量" }.orEmpty()
        return "运行诊断：${level.displayName()} · $readinessText · $recentText · $modelText$deviceText"
    }

    fun detailText(): String {
        val checksText = readiness.checks.joinToString("\n") { check ->
            "- [${check.severity}] ${check.title}: ${check.detail}"
        }
        val humanizationText = if (humanizationProfile.enabled) {
            "已启用 ${humanizationProfile.level}，时间随机=${humanizationProfile.timeRandomEnabled}，坐标随机=${humanizationProfile.positionRandomEnabled}"
        } else {
            "未启用"
        }
        val latestText = latestHistory?.let { entry ->
            "${entry.taskGoal} / ${entry.status}" +
                (entry.failureType?.let { " / failure=$it" } ?: "")
        } ?: "暂无"
        return buildString {
            appendLine("PhoneAgent 运行诊断")
            appendLine("生成时间: ${formatTime(generatedAt)}")
            appendLine("状态: ${level.displayName()}")
            appendLine("运行中: ${if (running) "是" else "否"}")
            appendLine("模式: $mode")
            appendLine("模型: $modelLabel")
            appendLine("设备: ${deviceSnapshot?.toDisplayText() ?: "未记录"}")
            deviceSnapshot?.lowBatteryWarning()?.let { appendLine("设备提示: $it") }
            appendLine("执行拟真: $humanizationText")
            appendLine("最近任务: ${recentSummary.toDisplayText()}")
            appendLine("最新历史: $latestText")
            appendLine("检查项:")
            appendLine(checksText.ifBlank { "- 无" })
        }.trimEnd()
    }

    private fun RuntimeDiagnosticLevel.displayName(): String {
        return when (this) {
            RuntimeDiagnosticLevel.HEALTHY -> "健康"
            RuntimeDiagnosticLevel.DEGRADED -> "需关注"
            RuntimeDiagnosticLevel.BLOCKED -> "阻塞"
            RuntimeDiagnosticLevel.RUNNING -> "运行中"
        }
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }
}

object RuntimeDiagnosticSnapshotBuilder {
    fun build(
        running: Boolean,
        mode: Mode,
        modelLabel: String,
        readiness: RunReadinessReport,
        humanizationProfile: ExecutionHumanizationProfile,
        recentSummary: RecentTaskPerformanceSummary,
        history: List<TaskHistoryEntry>,
        deviceSnapshot: RuntimeDeviceSnapshot? = null,
        generatedAt: Long = System.currentTimeMillis()
    ): RuntimeDiagnosticSnapshot {
        val latestHistory = history.maxByOrNull { it.startedAt }
        return RuntimeDiagnosticSnapshot(
            generatedAt = generatedAt,
            level = determineLevel(running, readiness, recentSummary, latestHistory),
            running = running,
            mode = mode,
            modelLabel = modelLabel,
            readiness = readiness,
            humanizationProfile = humanizationProfile,
            recentSummary = recentSummary,
            latestHistory = latestHistory,
            deviceSnapshot = deviceSnapshot
        )
    }

    private fun determineLevel(
        running: Boolean,
        readiness: RunReadinessReport,
        recentSummary: RecentTaskPerformanceSummary,
        latestHistory: TaskHistoryEntry?
    ): RuntimeDiagnosticLevel {
        return when {
            running -> RuntimeDiagnosticLevel.RUNNING
            readiness.blockers.isNotEmpty() -> RuntimeDiagnosticLevel.BLOCKED
            readiness.warnings.isNotEmpty() -> RuntimeDiagnosticLevel.DEGRADED
            recentSummary.failedCount > 0 -> RuntimeDiagnosticLevel.DEGRADED
            latestHistory?.status == TaskHistoryStatus.FAILED -> RuntimeDiagnosticLevel.DEGRADED
            else -> RuntimeDiagnosticLevel.HEALTHY
        }
    }
}
