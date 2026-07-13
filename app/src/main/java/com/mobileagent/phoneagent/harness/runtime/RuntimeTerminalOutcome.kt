package com.mobileagent.phoneagent.harness.runtime

import com.mobileagent.phoneagent.agent.AgentRuntimeState
import com.mobileagent.phoneagent.harness.act.ExecutionResult
import com.mobileagent.phoneagent.harness.recover.FailureType
import com.mobileagent.phoneagent.harness.trace.TaskHistoryStatus

data class RuntimeTerminalDecision(
    val stepStatus: StepStatus,
    val historyStatus: TaskHistoryStatus,
    val success: Boolean,
    val failureType: FailureType?
)

data class RuntimeClosureDecision(
    val historyStatus: TaskHistoryStatus,
    val message: String,
    val failureType: FailureType
)

object RuntimeTerminalOutcome {
    fun forInactiveState(state: AgentRuntimeState): RuntimeClosureDecision {
        return if (state == AgentRuntimeState.STOPPED) {
            RuntimeClosureDecision(
                historyStatus = TaskHistoryStatus.STOPPED,
                message = "任务被停止",
                failureType = FailureType.TASK_STOPPED
            )
        } else {
            RuntimeClosureDecision(
                historyStatus = TaskHistoryStatus.FAILED,
                message = "任务失败",
                failureType = FailureType.UNKNOWN
            )
        }
    }

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
