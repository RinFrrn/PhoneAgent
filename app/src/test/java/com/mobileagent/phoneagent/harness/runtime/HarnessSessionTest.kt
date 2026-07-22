package com.mobileagent.phoneagent.harness.runtime

import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.recover.FailureType
import com.mobileagent.phoneagent.harness.spec.TaskSpec
import com.mobileagent.phoneagent.harness.verify.VerificationResult
import com.mobileagent.phoneagent.model.ContentItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessSessionTest {
    private val taskSpec = TaskSpec(id = "test", goal = "打开微信", mode = "ACCESSIBILITY")
    private val tapAction = """{"_metadata":"do","action":"Tap","element":[500,500]}"""
    private val waitAction = """{"_metadata":"do","action":"Wait","duration":"1 seconds"}"""

    @Test
    fun repeatedIneffectiveTapOnSamePageIncrementsStagnationCount() {
        val session = HarnessSession(taskSpec)
        val page = page("通讯录 发现 搜索 服务")
        val failedVerification = VerificationResult(false, 0.85f, "页面未变化")

        val first = session.recordStepOutcome(
            actionJson = tapAction,
            before = page,
            after = page.copy(timestamp = page.timestamp + 1),
            verification = failedVerification
        )
        val second = session.recordStepOutcome(
            actionJson = tapAction,
            before = page,
            after = page.copy(timestamp = page.timestamp + 2),
            verification = failedVerification
        )
        val third = session.recordStepOutcome(
            actionJson = tapAction,
            before = page,
            after = page.copy(timestamp = page.timestamp + 3),
            verification = failedVerification
        )

        assertTrue(first.ineffective)
        assertFalse(first.repeatAction)
        assertEquals(1, first.consecutiveIneffectiveActions)
        assertTrue(second.repeatAction)
        assertEquals(2, second.consecutiveIneffectiveActions)
        assertEquals(3, third.consecutiveIneffectiveActions)
    }

    @Test
    fun waitDoesNotParticipateInStagnationCount() {
        val session = HarnessSession(taskSpec)
        val page = page("加载中")

        val result = session.recordStepOutcome(
            actionJson = waitAction,
            before = page,
            after = page.copy(timestamp = page.timestamp + 1),
            verification = VerificationResult(false, 0.7f, "等待后页面未变化")
        )

        assertFalse(result.ineffective)
        assertEquals(0, result.consecutiveIneffectiveActions)
    }

    @Test
    fun recoveryAttemptsAreTypedAndResetAfterSuccess() {
        val session = HarnessSession(taskSpec)

        assertEquals(1, session.recordRecoveryFailure(FailureType.OBSERVATION_FAILED))
        assertEquals(2, session.recordRecoveryFailure(FailureType.OBSERVATION_FAILED))
        assertEquals(1, session.recordRecoveryFailure(FailureType.MODEL_REQUEST_FAILED))

        session.resetRecoveryFailures(FailureType.OBSERVATION_FAILED)

        assertEquals(1, session.recordRecoveryFailure(FailureType.OBSERVATION_FAILED))
        assertEquals(2, session.recordRecoveryFailure(FailureType.MODEL_REQUEST_FAILED))
    }

    private fun page(text: String): Observation {
        return Observation(
            currentApp = "微信",
            currentPackage = "com.tencent.mm",
            contentItems = listOf(ContentItem(type = "text", text = text))
        )
    }
}
