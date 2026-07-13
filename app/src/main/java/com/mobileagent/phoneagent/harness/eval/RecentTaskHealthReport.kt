package com.mobileagent.phoneagent.harness.eval

import com.mobileagent.phoneagent.harness.recover.FailureType
import com.mobileagent.phoneagent.harness.trace.TaskHistoryEntry
import com.mobileagent.phoneagent.harness.trace.TaskHistoryStatus
import kotlin.math.roundToInt

enum class RecentTaskHealthLevel {
    NO_DATA,
    HEALTHY,
    WATCH,
    FAILING,
    BUSY
}

data class RecentTaskHealthReport(
    val level: RecentTaskHealthLevel,
    val taskCount: Int,
    val finishedCount: Int,
    val successCount: Int,
    val failedCount: Int,
    val stoppedCount: Int,
    val runningCount: Int,
    val successRatePercent: Int?,
    val averageSteps: Float?,
    val dominantFailureType: FailureType?,
    val dominantFailureCount: Int,
    val latestFailure: TaskHistoryEntry?,
    val recommendation: String
) {
    fun isEmpty(): Boolean = level == RecentTaskHealthLevel.NO_DATA

    fun toDisplayText(): String {
        if (isEmpty()) {
            return "任务健康：暂无历史数据"
        }
        val rateText = successRatePercent?.let { "成功率 $it%" } ?: "暂无完成"
        val stepsText = averageSteps?.let { "均步 ${formatSteps(it)}" } ?: "均步 --"
        val failureText = dominantFailureType?.let { "主要失败 $it($dominantFailureCount)" } ?: "无集中失败"
        return "任务健康：${level.displayName()} · $rateText · $stepsText · $failureText"
    }

    fun detailText(): String {
        if (isEmpty()) {
            return "任务健康：暂无历史数据"
        }
        val latestFailureText = latestFailure?.let { entry ->
            "${entry.taskGoal} / ${entry.failureType ?: "UNKNOWN"} / ${entry.outcomeMessage ?: "无结果说明"}"
        } ?: "无"
        return buildString {
            appendLine(toDisplayText())
            appendLine("样本: $taskCount 个，完成 $finishedCount，成功 $successCount，失败 $failedCount，停止 $stoppedCount，运行中 $runningCount")
            appendLine("最近失败: $latestFailureText")
            appendLine("建议: $recommendation")
        }.trimEnd()
    }

    private fun RecentTaskHealthLevel.displayName(): String {
        return when (this) {
            RecentTaskHealthLevel.NO_DATA -> "暂无"
            RecentTaskHealthLevel.HEALTHY -> "健康"
            RecentTaskHealthLevel.WATCH -> "观察"
            RecentTaskHealthLevel.FAILING -> "异常"
            RecentTaskHealthLevel.BUSY -> "运行中"
        }
    }

    private fun formatSteps(steps: Float): String {
        val rounded = (steps * 10).roundToInt() / 10f
        return if (rounded % 1f == 0f) {
            rounded.toInt().toString()
        } else {
            rounded.toString()
        }
    }
}

object RecentTaskHealthAnalyzer {
    fun analyze(history: List<TaskHistoryEntry>): RecentTaskHealthReport {
        if (history.isEmpty()) {
            return RecentTaskHealthReport(
                level = RecentTaskHealthLevel.NO_DATA,
                taskCount = 0,
                finishedCount = 0,
                successCount = 0,
                failedCount = 0,
                stoppedCount = 0,
                runningCount = 0,
                successRatePercent = null,
                averageSteps = null,
                dominantFailureType = null,
                dominantFailureCount = 0,
                latestFailure = null,
                recommendation = "先运行一个任务，系统会基于 trace 生成健康建议。"
            )
        }

        val finished = history.filter { it.status != TaskHistoryStatus.RUNNING }
        val successCount = history.count { it.status == TaskHistoryStatus.SUCCEEDED }
        val failedCount = history.count { it.status == TaskHistoryStatus.FAILED }
        val stoppedCount = history.count { it.status == TaskHistoryStatus.STOPPED }
        val runningCount = history.count { it.status == TaskHistoryStatus.RUNNING }
        val failureCounts = history
            .filter { it.status == TaskHistoryStatus.FAILED }
            .groupingBy { it.failureType ?: FailureType.UNKNOWN }
            .eachCount()
        val dominantFailure = failureCounts.maxByOrNull { it.value }
        val successRate = if (finished.isEmpty()) {
            null
        } else {
            ((successCount.toFloat() / finished.size.toFloat()) * 100).roundToInt()
        }
        val averageSteps = history
            .takeIf { it.isNotEmpty() }
            ?.let { entries -> entries.sumOf { it.totalSteps }.toFloat() / entries.size.toFloat() }
        val latestFailure = history
            .filter { it.status == TaskHistoryStatus.FAILED }
            .maxByOrNull { it.startedAt }
        val level = determineLevel(
            finishedCount = finished.size,
            successRate = successRate,
            failedCount = failedCount,
            runningCount = runningCount
        )
        return RecentTaskHealthReport(
            level = level,
            taskCount = history.size,
            finishedCount = finished.size,
            successCount = successCount,
            failedCount = failedCount,
            stoppedCount = stoppedCount,
            runningCount = runningCount,
            successRatePercent = successRate,
            averageSteps = averageSteps,
            dominantFailureType = dominantFailure?.key,
            dominantFailureCount = dominantFailure?.value ?: 0,
            latestFailure = latestFailure,
            recommendation = recommendationFor(level, dominantFailure?.key, runningCount)
        )
    }

    private fun determineLevel(
        finishedCount: Int,
        successRate: Int?,
        failedCount: Int,
        runningCount: Int
    ): RecentTaskHealthLevel {
        return when {
            finishedCount == 0 && runningCount > 0 -> RecentTaskHealthLevel.BUSY
            finishedCount == 0 -> RecentTaskHealthLevel.NO_DATA
            failedCount >= 2 && (successRate ?: 100) < 60 -> RecentTaskHealthLevel.FAILING
            (successRate ?: 100) < 80 || failedCount > 0 -> RecentTaskHealthLevel.WATCH
            else -> RecentTaskHealthLevel.HEALTHY
        }
    }

    private fun recommendationFor(
        level: RecentTaskHealthLevel,
        dominantFailureType: FailureType?,
        runningCount: Int
    ): String {
        if (runningCount > 0 && level == RecentTaskHealthLevel.BUSY) {
            return "当前有任务运行中，建议等待结束后再判断稳定性。"
        }
        return when (dominantFailureType) {
            FailureType.ACTION_NOT_EFFECTIVE ->
                "近期主要问题是动作未生效，建议检查页面停滞提示、点击目标和备用滑动策略。"
            FailureType.MODEL_AUTH, FailureType.MODEL_BALANCE, FailureType.MODEL_REQUEST_FAILED ->
                "近期主要问题来自模型请求，建议检查模型配置、额度、网络和 provider 返回内容。"
            FailureType.PERMISSION_MISSING ->
                "近期主要问题是权限缺失，建议重新检查无障碍、悬浮窗、通知和录屏授权。"
            FailureType.APP_NOT_FOUND, FailureType.APP_LAUNCH_BLOCKED,
            FailureType.APP_LAUNCH_CONFIRMATION_REQUIRED, FailureType.APP_LAUNCH_TARGET_NOT_REACHED ->
                "近期主要问题是应用启动链路，建议补充应用别名或确认目标应用安装及启动确认弹窗。"
            FailureType.MAX_STEPS_EXCEEDED ->
                "近期任务容易超步数，建议拆小任务目标或补充更明确的完成条件。"
            FailureType.RUNTIME_INTERRUPTED ->
                "近期存在应用进程中断，建议检查系统后台限制、崩溃日志和前台服务状态。"
            FailureType.USER_TAKEOVER_REQUIRED ->
                "近期任务常需要用户接管，建议把登录、验证码或支付确认等敏感步骤提前标注。"
            null -> when (level) {
                RecentTaskHealthLevel.HEALTHY -> "近期任务稳定，可以继续扩大任务覆盖面。"
                RecentTaskHealthLevel.WATCH -> "近期存在失败或成功率下降，建议打开最近 trace 查看具体步骤。"
                RecentTaskHealthLevel.FAILING -> "近期失败偏多，建议优先查看主失败类型和最新失败 trace。"
                RecentTaskHealthLevel.BUSY -> "当前有任务运行中，建议等待结束后再判断稳定性。"
                RecentTaskHealthLevel.NO_DATA -> "先运行一个任务，系统会基于 trace 生成健康建议。"
            }
            else ->
                "近期存在 ${dominantFailureType.name}，建议打开最近 trace 定位失败步骤。"
        }
    }
}
