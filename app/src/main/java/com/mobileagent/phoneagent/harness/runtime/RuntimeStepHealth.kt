package com.mobileagent.phoneagent.harness.runtime

enum class RuntimeWarningSeverity {
    INFO,
    WARNING,
    CRITICAL
}

data class RuntimeWarning(
    val id: String,
    val severity: RuntimeWarningSeverity,
    val message: String
)

data class RuntimeStepTiming(
    val totalMs: Long,
    val observationMs: Long = 0L,
    val planningMs: Long = 0L,
    val executionMs: Long = 0L,
    val verificationMs: Long = 0L
) {
    fun warnings(): List<RuntimeWarning> {
        return RuntimeStepTimingMonitor.warningsForTiming(this)
    }

    fun toDisplayText(): String {
        return "总耗时 ${totalMs}ms（观察 ${observationMs}ms，规划 ${planningMs}ms，执行 ${executionMs}ms，验证 ${verificationMs}ms）"
    }
}

object RuntimeStepTimingMonitor {
    fun warningsForTiming(timing: RuntimeStepTiming): List<RuntimeWarning> {
        return when {
            timing.totalMs >= CRITICAL_STEP_MS -> listOf(
                RuntimeWarning(
                    id = "slow_step_critical",
                    severity = RuntimeWarningSeverity.CRITICAL,
                    message = "本步骤耗时 ${timing.totalMs}ms，明显偏慢；建议检查模型响应、页面加载或用户等待环节。"
                )
            )
            timing.totalMs >= SLOW_STEP_MS -> listOf(
                RuntimeWarning(
                    id = "slow_step",
                    severity = RuntimeWarningSeverity.WARNING,
                    message = "本步骤耗时 ${timing.totalMs}ms，建议关注是否存在慢模型调用或页面加载。"
                )
            )
            else -> emptyList()
        }
    }

    private const val SLOW_STEP_MS = 15_000L
    private const val CRITICAL_STEP_MS = 30_000L
}

object RuntimeStepHealthMonitor {
    fun warningsForStep(stepIndex: Int, maxSteps: Int): List<RuntimeWarning> {
        if (stepIndex <= 0) {
            return emptyList()
        }

        val warnings = mutableListOf<RuntimeWarning>()
        if (stepIndex == LONG_TASK_STEP) {
            warnings += RuntimeWarning(
                id = "long_task",
                severity = RuntimeWarningSeverity.WARNING,
                message = "任务已执行 $LONG_TASK_STEP 步，建议检查是否存在重复路径或页面卡住。"
            )
        }
        if (stepIndex == EXTREME_TASK_STEP) {
            warnings += RuntimeWarning(
                id = "very_long_task",
                severity = RuntimeWarningSeverity.CRITICAL,
                message = "任务已执行 $EXTREME_TASK_STEP 步，若仍未完成，应考虑请求用户接管或缩小目标。"
            )
        }

        if (maxSteps > 0 && maxSteps != Int.MAX_VALUE) {
            if (crossedPercent(stepIndex, maxSteps, WARNING_STEP_PERCENT)) {
                warnings += RuntimeWarning(
                    id = "max_steps_warning",
                    severity = RuntimeWarningSeverity.WARNING,
                    message = "任务已使用约 $WARNING_STEP_PERCENT% 步数预算，后续应优先收敛到完成条件。"
                )
            }
            if (crossedPercent(stepIndex, maxSteps, CRITICAL_STEP_PERCENT)) {
                warnings += RuntimeWarning(
                    id = "max_steps_critical",
                    severity = RuntimeWarningSeverity.CRITICAL,
                    message = "任务即将达到最大步数 $maxSteps，下一步应完成任务或明确失败原因。"
                )
            }
        }

        estimatedContextUsage(stepIndex)?.let { usage ->
            if (crossedContextPercent(usage.previousPercent, usage.currentPercent, CONTEXT_WARNING_PERCENT)) {
                warnings += RuntimeWarning(
                    id = "context_budget_warning",
                    severity = RuntimeWarningSeverity.WARNING,
                    message = "估算上下文已使用约 ${usage.currentPercent}%，后续应减少重复观察、压缩历史或尽快收敛到完成条件。"
                )
            }
            if (crossedContextPercent(usage.previousPercent, usage.currentPercent, CONTEXT_CRITICAL_PERCENT)) {
                warnings += RuntimeWarning(
                    id = "context_budget_critical",
                    severity = RuntimeWarningSeverity.CRITICAL,
                    message = "估算上下文已使用约 ${usage.currentPercent}%，任务可能接近模型上下文上限；建议拆分任务、总结已完成内容或请求用户确认继续。"
                )
            }
        }
        return warnings
    }

    private fun crossedPercent(stepIndex: Int, maxSteps: Int, threshold: Int): Boolean {
        val previous = ((stepIndex - 1) * 100) / maxSteps
        val current = (stepIndex * 100) / maxSteps
        return previous < threshold && current >= threshold
    }

    private fun crossedContextPercent(previousPercent: Int, currentPercent: Int, threshold: Int): Boolean {
        return previousPercent < threshold && currentPercent >= threshold
    }

    private fun estimatedContextUsage(stepIndex: Int): EstimatedContextUsage? {
        if (stepIndex <= 1) {
            return null
        }
        val previousTokens = estimatedContextTokens(stepIndex - 1)
        val currentTokens = estimatedContextTokens(stepIndex)
        return EstimatedContextUsage(
            previousPercent = ((previousTokens * 100) / CONTEXT_LIMIT_TOKENS).toInt(),
            currentPercent = ((currentTokens * 100) / CONTEXT_LIMIT_TOKENS).toInt()
        )
    }

    private fun estimatedContextTokens(stepIndex: Int): Long {
        return BASE_CONTEXT_TOKENS + (stepIndex - 1L) * TOKENS_PER_STEP
    }

    private data class EstimatedContextUsage(
        val previousPercent: Int,
        val currentPercent: Int
    )

    private const val LONG_TASK_STEP = 10
    private const val EXTREME_TASK_STEP = 30
    private const val WARNING_STEP_PERCENT = 70
    private const val CRITICAL_STEP_PERCENT = 90
    private const val BASE_CONTEXT_TOKENS = 6_000L
    private const val TOKENS_PER_STEP = 250L
    private const val CONTEXT_LIMIT_TOKENS = 16_000L
    private const val CONTEXT_WARNING_PERCENT = 70
    private const val CONTEXT_CRITICAL_PERCENT = 85
}
