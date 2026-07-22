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
        assertEquals(RecoveryRoute.USER_INTERVENTION, decision.route)
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

    @Test
    fun transientObservationFailureRetriesTwiceThenStops() {
        val taskSpec = TaskSpec(id = "task", goal = "打开微信", mode = "VISION")
        val observation = Observation(
            currentApp = null,
            contentItems = emptyList(),
            failureMessage = "截图暂时为空"
        )

        val first = policy.decide(
            FailureType.OBSERVATION_FAILED,
            taskSpec,
            observation,
            null,
            RecoveryContext(attempt = 1)
        )
        val second = policy.decide(
            FailureType.OBSERVATION_FAILED,
            taskSpec,
            observation,
            null,
            RecoveryContext(attempt = 2)
        )
        val exhausted = policy.decide(
            FailureType.OBSERVATION_FAILED,
            taskSpec,
            observation,
            null,
            RecoveryContext(attempt = 3)
        )

        assertEquals(RecoveryRoute.RETRY, first.route)
        assertEquals(RecoveryRoute.RETRY, second.route)
        assertEquals(3, first.maxAttempts)
        assertTrue(first.delayMs > 0)
        assertEquals(RecoveryRoute.STOP, exhausted.route)
        assertTrue(exhausted.stopTask)
    }

    @Test
    fun modelAuthFailureStopsWithoutRetry() {
        val taskSpec = TaskSpec(id = "task", goal = "打开微信", mode = "ACCESSIBILITY")
        val observation = Observation(currentApp = "桌面", contentItems = emptyList())

        val decision = policy.decide(
            FailureType.MODEL_AUTH,
            taskSpec,
            observation,
            null,
            RecoveryContext(attempt = 1)
        )

        assertEquals(RecoveryRoute.STOP, decision.route)
        assertEquals(0L, decision.delayMs)
    }

    @Test
    fun repeatedIneffectiveActionEscalatesToUserIntervention() {
        val taskSpec = TaskSpec(id = "task", goal = "打开微信", mode = "ACCESSIBILITY")
        val observation = Observation(currentApp = "微信", contentItems = emptyList())
        val execution = ExecutionResult(
            success = false,
            shouldFinish = false,
            message = "连续三次点击无效",
            actionJson = """{"_metadata":"do","action":"Tap","element":[500,900]}""",
            requiresTakeover = true,
            failureType = FailureType.ACTION_NOT_EFFECTIVE
        )

        val decision = policy.decide(
            FailureType.ACTION_NOT_EFFECTIVE,
            taskSpec,
            observation,
            execution,
            RecoveryContext(attempt = 3)
        )
        val trace = decision.toTrace(FailureType.ACTION_NOT_EFFECTIVE, RecoveryContext(3))

        assertEquals(RecoveryRoute.USER_INTERVENTION, decision.route)
        assertTrue(decision.requiresUserTakeover)
        assertEquals(RecoveryRoute.USER_INTERVENTION, trace.route)
        assertEquals(3, trace.attempt)
        assertEquals(FailureType.ACTION_NOT_EFFECTIVE, trace.failureType)
    }
}
