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
        return warnings
    }

    private fun crossedPercent(stepIndex: Int, maxSteps: Int, threshold: Int): Boolean {
        val previous = ((stepIndex - 1) * 100) / maxSteps
        val current = (stepIndex * 100) / maxSteps
        return previous < threshold && current >= threshold
    }

    private const val LONG_TASK_STEP = 10
    private const val EXTREME_TASK_STEP = 30
    private const val WARNING_STEP_PERCENT = 70
    private const val CRITICAL_STEP_PERCENT = 90
}
