package com.mobileagent.phoneagent.harness.runtime

import com.mobileagent.phoneagent.harness.act.ExecutionResult
import com.mobileagent.phoneagent.harness.recover.FailureType
import com.mobileagent.phoneagent.harness.trace.TaskHistoryStatus

data class RuntimeTerminalDecision(
    val stepStatus: StepStatus,
    val historyStatus: TaskHistoryStatus,
    val success: Boolean,
    val failureType: FailureType?
)

object RuntimeTerminalOutcome {
    fun decide(
        terminalRequested: Boolean,
        execution: ExecutionResult
    ): RuntimeTerminalDecision? {
        if (!terminalRequested) {
            return null
        }
        return if (execution.success) {
            RuntimeTerminalDecision(
                stepStatus = StepStatus.FINISHED,
                historyStatus = TaskHistoryStatus.SUCCEEDED,
                success = true,
                failureType = null
            )
        } else {
            RuntimeTerminalDecision(
                stepStatus = StepStatus.FAILED,
                historyStatus = TaskHistoryStatus.FAILED,
                success = false,
                failureType = execution.failureType ?: FailureType.UNKNOWN
            )
        }
    }
}
