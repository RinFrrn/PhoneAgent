package com.mobileagent.phoneagent.harness.recover

import com.mobileagent.phoneagent.action.UserInteractionKind
import com.mobileagent.phoneagent.harness.act.ExecutionResult
import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.spec.TaskSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPolicyTest {
    private val policy = DefaultRecoveryPolicy()

    @Test
    fun sensitiveConfirmationRequiresUserTakeover() {
        val decision = policy.decide(
            failureType = FailureType.SENSITIVE_CONFIRMATION_REQUIRED,
            taskSpec = TaskSpec(id = "task", goal = "给张三转账100元", mode = "ACCESSIBILITY"),
            observation = Observation(currentApp = "支付宝", contentItems = emptyList()),
            execution = ExecutionResult(
                success = false,
                shouldFinish = false,
                message = "检测到敏感确认页面",
                actionJson = """{"_metadata":"do","action":"Tap","element":[500,900]}"""
            )
        )

        assertTrue(decision.requiresUserTakeover)
        assertFalse(decision.stopTask)
        assertTrue(decision.userMessage.orEmpty().contains("敏感"))
        assertEquals(
            UserInteractionKind.SENSITIVE_CONFIRMATION,
            decision.userInteractionRequest?.kind
        )
    }

    @Test
    fun userDenialAndTimeoutStopTask() {
        val taskSpec = TaskSpec(id = "task", goal = "给张三转账100元", mode = "ACCESSIBILITY")
        val observation = Observation(currentApp = "支付宝", contentItems = emptyList())

        val denied = policy.decide(FailureType.USER_DENIED, taskSpec, observation, null)
        val timedOut = policy.decide(FailureType.USER_INTERVENTION_TIMEOUT, taskSpec, observation, null)

        assertTrue(denied.stopTask)
        assertTrue(timedOut.stopTask)
        assertEquals(false, denied.requiresUserTakeover)
        assertEquals(false, timedOut.requiresUserTakeover)
    }
}
