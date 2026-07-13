package com.mobileagent.phoneagent.harness.runtime

import com.mobileagent.phoneagent.agent.AgentRuntimeState
import com.mobileagent.phoneagent.harness.act.ExecutionResult
import com.mobileagent.phoneagent.harness.recover.FailureType
import com.mobileagent.phoneagent.harness.trace.TaskHistoryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeTerminalOutcomeTest {
    @Test
    fun stoppedRuntimeClosesAsTaskStopped() {
        val decision = RuntimeTerminalOutcome.forInactiveState(AgentRuntimeState.STOPPED)

        assertEquals(TaskHistoryStatus.STOPPED, decision.historyStatus)
        assertEquals("任务被停止", decision.message)
        assertEquals(FailureType.TASK_STOPPED, decision.failureType)
    }

    @Test
    fun unexpectedInactiveRuntimeRemainsTypedFailure() {
        val decision = RuntimeTerminalOutcome.forInactiveState(AgentRuntimeState.FAILED)

        assertEquals(TaskHistoryStatus.FAILED, decision.historyStatus)
        assertEquals(FailureType.UNKNOWN, decision.failureType)
    }

    @Test
    fun successfulTerminationClosesAsSucceeded() {
        val decision = RuntimeTerminalOutcome.decide(
            terminalRequested = true,
            execution = execution(success = true)
        )

        assertEquals(StepStatus.FINISHED, decision?.stepStatus)
        assertEquals(TaskHistoryStatus.SUCCEEDED, decision?.historyStatus)
        assertEquals(true, decision?.success)
        assertNull(decision?.failureType)
    }

    @Test
    fun failedTerminationClosesAsFailed() {
        val decision = RuntimeTerminalOutcome.decide(
            terminalRequested = true,
            execution = execution(success = false)
        )

        assertEquals(StepStatus.FAILED, decision?.stepStatus)
        assertEquals(TaskHistoryStatus.FAILED, decision?.historyStatus)
        assertEquals(false, decision?.success)
        assertEquals(FailureType.ACTION_EXECUTION_FAILED, decision?.failureType)
    }

    @Test
    fun noDecisionWhenTerminationNotRequested() {
        assertNull(
            RuntimeTerminalOutcome.decide(
                terminalRequested = false,
                execution = execution(success = true)
            )
        )
    }

    private fun execution(success: Boolean): ExecutionResult {
        return ExecutionResult(
            success = success,
            shouldFinish = true,
            message = "done",
            actionJson = """{"action":"done","success":$success}""",
            failureType = if (success) null else FailureType.ACTION_EXECUTION_FAILED
        )
    }
}
