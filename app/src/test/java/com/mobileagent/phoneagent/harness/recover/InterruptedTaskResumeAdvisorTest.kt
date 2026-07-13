package com.mobileagent.phoneagent.harness.recover

import com.mobileagent.phoneagent.action.UserInteractionKind
import com.mobileagent.phoneagent.action.UserInteractionRequest
import com.mobileagent.phoneagent.harness.act.ExecutionResult
import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.plan.PlanDecision
import com.mobileagent.phoneagent.harness.runtime.StepStatus
import com.mobileagent.phoneagent.harness.trace.SessionTrace
import com.mobileagent.phoneagent.harness.trace.StepTrace
import com.mobileagent.phoneagent.harness.trace.TaskHistoryEntry
import com.mobileagent.phoneagent.harness.trace.TaskHistoryStatus
import com.mobileagent.phoneagent.harness.trace.TraceSanitizer
import com.mobileagent.phoneagent.harness.verify.VerificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InterruptedTaskResumeAdvisorTest {
    @Test
    fun interruptedTaskBuildsFreshObservationResumePlanWithoutOldAction() {
        val assessment = InterruptedTaskResumeAdvisor.assess(
            entry = historyEntry(),
            trace = trace(
                steps = listOf(
                    step(
                        actionJson = """{"action":"Tap","element":[321,654]}""",
                        verification = VerificationResult(
                            passed = true,
                            confidence = 0.9f,
                            reason = "已进入设置页",
                            observedChange = "标题变为网络和互联网"
                        )
                    )
                )
            )
        )

        assertTrue(assessment.canResume())
        val plan = assessment.plan
        assertNotNull(plan)
        assertEquals("source-session", plan?.context?.sourceSessionId)
        assertEquals(1, plan?.context?.completedStepCount)
        assertEquals("设置", plan?.context?.lastKnownApp)
        val memoryText = plan?.context?.toMemoryText().orEmpty()
        assertTrue(memoryText.contains("重新观察当前屏幕"))
        assertTrue(memoryText.contains("不得重放旧坐标"))
        assertFalse(memoryText.contains("321"))
        assertFalse(memoryText.contains("654"))
    }

    @Test
    fun sensitiveCheckpointCannotResumeAutomatically() {
        val sensitiveRequest = UserInteractionRequest(
            question = "是否确认支付？",
            options = listOf("确认", "取消"),
            reason = "支付前确认",
            kind = UserInteractionKind.SENSITIVE_CONFIRMATION
        )
        val assessment = InterruptedTaskResumeAdvisor.assess(
            entry = historyEntry(),
            trace = trace(
                steps = listOf(
                    step(
                        execution = ExecutionResult(
                            success = false,
                            shouldFinish = false,
                            message = "等待确认",
                            actionJson = """{"action":"Ask_User"}""",
                            failureType = FailureType.SENSITIVE_CONFIRMATION_REQUIRED,
                            userInteractionRequest = sensitiveRequest
                        )
                    )
                )
            )
        )

        assertFalse(assessment.canResume())
        assertEquals(TaskResumeEligibility.SENSITIVE_CHECKPOINT, assessment.eligibility)
    }

    @Test
    fun missingOrNonInterruptedTraceIsRejected() {
        val missing = InterruptedTaskResumeAdvisor.assess(historyEntry(), null)
        val normalFailure = InterruptedTaskResumeAdvisor.assess(
            historyEntry().copy(
                status = TaskHistoryStatus.FAILED,
                failureType = FailureType.ACTION_EXECUTION_FAILED
            ),
            trace()
        )
        val redactedGoal = InterruptedTaskResumeAdvisor.assess(
            historyEntry(),
            trace().copy(taskGoal = "给 [REDACTED] 发送消息")
        )
        val mismatchedTrace = InterruptedTaskResumeAdvisor.assess(
            historyEntry(),
            trace().copy(sessionId = "different-session")
        )

        assertEquals(TaskResumeEligibility.TRACE_MISSING, missing.eligibility)
        assertEquals(TaskResumeEligibility.NOT_INTERRUPTED, normalFailure.eligibility)
        assertEquals(TaskResumeEligibility.GOAL_REDACTED, redactedGoal.eligibility)
        assertEquals(TaskResumeEligibility.TRACE_MISMATCH, mismatchedTrace.eligibility)
    }

    private fun historyEntry(): TaskHistoryEntry {
        return TaskHistoryEntry(
            sessionId = "source-session",
            taskId = "source-task",
            taskGoal = "打开设置并查看网络",
            mode = "ACCESSIBILITY",
            startedAt = 100L,
            completedAt = 200L,
            status = TaskHistoryStatus.STOPPED,
            success = false,
            failureType = FailureType.RUNTIME_INTERRUPTED
        )
    }

    private fun trace(steps: List<StepTrace> = emptyList()): SessionTrace {
        return SessionTrace(
            sessionId = "source-session",
            taskId = "source-task",
            taskGoal = "打开设置并查看网络",
            mode = "ACCESSIBILITY",
            startedAt = 100L,
            completedAt = 200L,
            success = false,
            totalSteps = steps.size,
            steps = steps,
            status = TaskHistoryStatus.STOPPED,
            dataPolicy = TraceSanitizer.DATA_POLICY,
            failureType = FailureType.RUNTIME_INTERRUPTED
        )
    }

    private fun step(
        actionJson: String = """{"action":"Wait"}""",
        execution: ExecutionResult? = null,
        verification: VerificationResult? = null
    ): StepTrace {
        val observation = Observation(
            currentApp = "设置",
            currentPackage = "com.android.settings",
            contentItems = emptyList()
        )
        return StepTrace(
            stepIndex = 1,
            timestamp = 150L,
            status = StepStatus.EXECUTED,
            observationBefore = observation,
            decision = PlanDecision("继续任务", actionJson, actionJson),
            execution = execution,
            observationAfter = observation,
            verification = verification
        )
    }
}
