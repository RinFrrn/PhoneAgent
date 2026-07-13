package com.mobileagent.phoneagent.harness.recover

import com.mobileagent.phoneagent.action.UserInteractionKind
import com.mobileagent.phoneagent.harness.spec.TaskResumeContext
import com.mobileagent.phoneagent.harness.trace.SessionTrace
import com.mobileagent.phoneagent.harness.trace.TaskHistoryEntry
import com.mobileagent.phoneagent.harness.trace.TaskHistoryStatus
import com.mobileagent.phoneagent.harness.trace.TraceSanitizer

enum class TaskResumeEligibility {
    ELIGIBLE,
    NOT_INTERRUPTED,
    TRACE_MISSING,
    TRACE_MISMATCH,
    TRACE_STATE_MISMATCH,
    TRACE_NOT_MINIMIZED,
    GOAL_UNAVAILABLE,
    GOAL_REDACTED,
    MODE_UNSUPPORTED,
    PENDING_USER_DECISION,
    SENSITIVE_CHECKPOINT
}

data class TaskResumePlan(
    val taskGoal: String,
    val mode: String,
    val context: TaskResumeContext
)

data class TaskResumeAssessment(
    val eligibility: TaskResumeEligibility,
    val reason: String,
    val plan: TaskResumePlan? = null
) {
    fun canResume(): Boolean = eligibility == TaskResumeEligibility.ELIGIBLE && plan != null
}

object InterruptedTaskResumeAdvisor {
    fun assess(
        entry: TaskHistoryEntry,
        trace: SessionTrace?
    ): TaskResumeAssessment {
        if (
            entry.status != TaskHistoryStatus.STOPPED ||
            entry.failureType != FailureType.RUNTIME_INTERRUPTED
        ) {
            return blocked(TaskResumeEligibility.NOT_INTERRUPTED, "只有异常中断的任务可以安全续跑")
        }
        if (trace == null) {
            return blocked(TaskResumeEligibility.TRACE_MISSING, "原任务 Trace 缺失，无法构建安全恢复上下文")
        }
        if (trace.sessionId != entry.traceSessionId && trace.sessionId != entry.sessionId) {
            return blocked(TaskResumeEligibility.TRACE_MISMATCH, "历史索引与 Trace 不匹配，不能安全续跑")
        }
        if (
            trace.status != TaskHistoryStatus.STOPPED ||
            trace.failureType != FailureType.RUNTIME_INTERRUPTED ||
            trace.success != false
        ) {
            return blocked(TaskResumeEligibility.TRACE_STATE_MISMATCH, "原 Trace 的中断状态不一致，不能安全续跑")
        }
        if (trace.dataPolicy != TraceSanitizer.DATA_POLICY) {
            return blocked(TaskResumeEligibility.TRACE_NOT_MINIMIZED, "原任务 Trace 尚未完成脱敏迁移")
        }
        if (trace.taskGoal.isBlank()) {
            return blocked(TaskResumeEligibility.GOAL_UNAVAILABLE, "原任务目标为空，请重新输入任务")
        }
        if (REDACTION_MARKERS.any { marker -> marker in trace.taskGoal }) {
            return blocked(
                TaskResumeEligibility.GOAL_REDACTED,
                "原任务目标包含已脱敏内容，请重新输入完整目标后再运行"
            )
        }
        if (trace.mode !in SUPPORTED_MODES) {
            return blocked(TaskResumeEligibility.MODE_UNSUPPORTED, "原任务运行模式不受支持")
        }

        val lastStep = trace.steps.maxByOrNull { it.stepIndex }
        val request = lastStep?.execution?.userInteractionRequest
        if (request?.kind == UserInteractionKind.SENSITIVE_CONFIRMATION || hasSensitiveFailure(lastStep)) {
            return blocked(
                TaskResumeEligibility.SENSITIVE_CHECKPOINT,
                "中断点涉及敏感确认，必须由用户重新发起或确认，不能自动续跑"
            )
        }
        if (request != null) {
            return blocked(
                TaskResumeEligibility.PENDING_USER_DECISION,
                "中断时仍在等待用户选择，不能推断旧回答"
            )
        }

        val lastObservation = lastStep?.observationAfter ?: lastStep?.observationBefore
        val verificationSummary = lastStep?.verification?.let { verification ->
            listOfNotNull(
                verification.reason.takeIf { it.isNotBlank() },
                verification.observedChange?.takeIf { it.isNotBlank() }
            ).joinToString("；").take(240)
        }
        return TaskResumeAssessment(
            eligibility = TaskResumeEligibility.ELIGIBLE,
            reason = "将创建新 Session，先重新观察页面再继续规划",
            plan = TaskResumePlan(
                taskGoal = trace.taskGoal,
                mode = trace.mode,
                context = TaskResumeContext(
                    sourceSessionId = trace.sessionId,
                    completedStepCount = trace.totalSteps,
                    lastKnownApp = lastObservation?.currentApp?.take(120),
                    lastVerifiedSummary = verificationSummary
                )
            )
        )
    }

    private fun hasSensitiveFailure(step: com.mobileagent.phoneagent.harness.trace.StepTrace?): Boolean {
        val failureTypes = setOfNotNull(step?.failureType, step?.execution?.failureType)
        return failureTypes.any { it in SENSITIVE_FAILURES }
    }

    private fun blocked(eligibility: TaskResumeEligibility, reason: String): TaskResumeAssessment {
        return TaskResumeAssessment(eligibility = eligibility, reason = reason)
    }

    private val SUPPORTED_MODES = setOf("VISION", "ACCESSIBILITY", "HYBRID")
    private val REDACTION_MARKERS = setOf("[REDACTED]", "[CODE_REDACTED]", "[NUMBER_REDACTED]", "****", "***")
    private val SENSITIVE_FAILURES = setOf(
        FailureType.SENSITIVE_CONFIRMATION_REQUIRED,
        FailureType.USER_DENIED,
        FailureType.USER_INTERVENTION_TIMEOUT
    )
}
