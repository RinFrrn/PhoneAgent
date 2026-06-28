package com.mobileagent.phoneagent.agent

import com.mobileagent.phoneagent.action.ActionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FailureTrackerTest {
    private val tapAction = """{"_metadata":"do","action":"Tap","element":[500,500]}"""

    @Test
    fun replanPromptDoesNotClearFailureEscalationCount() {
        val tracker = FailureTracker()

        tracker.recordActionResult(tapAction, failedResult(), ineffective = true)
        tracker.recordActionResult(tapAction, failedResult(), ineffective = true)

        val prompt = tracker.consumeReplanPrompt("打开微信")

        assertNotNull(prompt)
        assertEquals(2, tracker.consecutiveFailures)
        assertEquals(2, tracker.consecutiveIneffectiveActions)
        assertNull(tracker.consumeReplanPrompt("打开微信"))
    }

    @Test
    fun ineffectiveActionsCanEscalateToUserInterventionPrompt() {
        val tracker = FailureTracker()

        repeat(5) {
            tracker.recordActionResult(tapAction, failedResult(), ineffective = true)
        }

        val prompt = tracker.maybeUserInterventionPrompt()

        assertNotNull(prompt)
        assertEquals(5, tracker.consecutiveFailures)
        assertEquals(5, tracker.consecutiveIneffectiveActions)
        assertNull(tracker.maybeUserInterventionPrompt())
    }

    @Test
    fun successClearsFailureAndIneffectiveCounts() {
        val tracker = FailureTracker()
        tracker.recordActionResult(tapAction, failedResult(), ineffective = true)

        tracker.recordActionResult(
            tapAction,
            ActionResult(success = true, shouldFinish = false)
        )

        assertEquals(0, tracker.consecutiveFailures)
        assertEquals(0, tracker.consecutiveIneffectiveActions)
    }

    private fun failedResult(): ActionResult {
        return ActionResult(
            success = false,
            shouldFinish = false,
            message = "页面没有变化"
        )
    }
}
