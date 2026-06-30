package com.mobileagent.phoneagent.harness.runtime

import com.mobileagent.phoneagent.agent.Mode
import com.mobileagent.phoneagent.harness.act.ExecutionHumanizationProfile
import com.mobileagent.phoneagent.harness.trace.ModelCallHealthAnalyzer
import com.mobileagent.phoneagent.harness.trace.ModelCallHealthLevel
import com.mobileagent.phoneagent.harness.trace.ModelUsageTrendReport
import com.mobileagent.phoneagent.harness.trace.RecentTaskPerformanceSummary
import com.mobileagent.phoneagent.harness.trace.TaskHistoryEntry
import com.mobileagent.phoneagent.harness.trace.TaskHistoryStatus
import com.mobileagent.phoneagent.harness.trace.TraceStorageReport
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
    val deviceSnapshot: RuntimeDeviceSnapshot? = null,
    val modelUsageTrend: ModelUsageTrendReport? = null,
    val traceStorageReport: TraceStorageReport? = null
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
        val modelHealth = ModelCallHealthAnalyzer.analyze(recentSummary.modelCallSummary)
        val modelText = if (recentSummary.modelCallSummary.isEmpty()) {
            "模型未统计"
        } else if (modelHealth.level == ModelCallHealthLevel.SLOW || modelHealth.level == ModelCallHealthLevel.HEAVY) {
            "模型${modelHealth.level.displayName()}"
        } else {
            "模型均耗 ${recentSummary.modelCallSummary.averageLatencyMs}ms"
        }
        val deviceText = deviceSnapshot?.compactHealthLabel()?.let { " · $it" }.orEmpty()
        val traceText = traceStorageReport
            ?.takeIf { it.hasWarnings() }
            ?.let { " · Trace需关注" }
            .orEmpty()
        return "运行诊断：${level.displayName()} · $readinessText · $recentText · $modelText$deviceText$traceText"
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
        val modelHealth = ModelCallHealthAnalyzer.analyze(recentSummary.modelCallSummary)
        return buildString {
            appendLine("PhoneAgent 运行诊断")
            appendLine("生成时间: ${formatTime(generatedAt)}")
            appendLine("状态: ${level.displayName()}")
            appendLine("运行中: ${if (running) "是" else "否"}")
            appendLine("模式: $mode")
            appendLine("模型: $modelLabel")
            appendLine("设备: ${deviceSnapshot?.toDisplayText() ?: "未记录"}")
            deviceSnapshot?.allHealthWarnings().orEmpty().forEach { warning ->
                appendLine("设备提示: $warning")
            }
            appendLine("执行拟真: $humanizationText")
            appendLine(modelHealth.detailText())
            modelUsageTrend?.let { appendLine(it.detailText()) }
            appendLine(traceStorageReport?.detailText() ?: "Trace 存储: 未记录")
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

    private fun ModelCallHealthLevel.displayName(): String {
        return when (this) {
            ModelCallHealthLevel.NO_DATA -> "未记录"
            ModelCallHealthLevel.HEALTHY -> "健康"
            ModelCallHealthLevel.WATCH -> "需观察"
            ModelCallHealthLevel.SLOW -> "偏慢"
            ModelCallHealthLevel.HEAVY -> "负载偏高"
        }
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }

    private fun RuntimeDeviceSnapshot.allHealthWarnings(): List<String> {
        return blockingWarnings() + advisoryWarnings()
    }

    private fun RuntimeDeviceSnapshot.compactHealthLabel(): String? {
        return when {
            keyguardLocked == true -> "设备锁屏"
            interactive == false -> "屏幕关闭"
            lowBatteryWarning() != null -> "低电量"
            powerSaveMode == true -> "省电模式"
            else -> null
        }
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
        modelUsageTrend: ModelUsageTrendReport? = null,
        traceStorageReport: TraceStorageReport? = null,
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
            deviceSnapshot = deviceSnapshot,
            modelUsageTrend = modelUsageTrend,
            traceStorageReport = traceStorageReport
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
