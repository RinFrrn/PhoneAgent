package com.mobileagent.phoneagent.harness.runtime

import com.mobileagent.phoneagent.action.UserInteractionKind
import com.mobileagent.phoneagent.action.UserInteractionRequest
import com.mobileagent.phoneagent.agent.UserActionResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class UserInterventionOutcomeResolverTest {
    private val sensitiveRequest = UserInteractionRequest(
        question = "是否确认继续自动化？",
        options = listOf("确认继续", "取消任务"),
        reason = "敏感任务需要用户明确确认",
        kind = UserInteractionKind.SENSITIVE_CONFIRMATION
    )

    @Test
    fun explicitConfirmationContinuesSensitiveTask() {
        val outcome = UserInterventionOutcomeResolver.resolve(
            request = sensitiveRequest,
            response = UserActionResponse(answer = "确认继续")
        )

        assertEquals(UserInterventionOutcome.CONTINUE, outcome)
    }

    @Test
    fun cancellationAndBlankAnswerDenySensitiveTask() {
        assertEquals(
            UserInterventionOutcome.DENIED,
            UserInterventionOutcomeResolver.resolve(
                request = sensitiveRequest,
                response = UserActionResponse(answer = "取消任务")
            )
        )
        assertEquals(
            UserInterventionOutcome.DENIED,
            UserInterventionOutcomeResolver.resolve(
                request = sensitiveRequest,
                response = UserActionResponse(answer = " ")
            )
        )
    }

    @Test
    fun timeoutStopsSensitiveTask() {
        val outcome = UserInterventionOutcomeResolver.resolve(
            request = sensitiveRequest,
            response = UserActionResponse.timeout()
        )

        assertEquals(UserInterventionOutcome.TIMED_OUT, outcome)
    }

    @Test
    fun ordinaryQuestionDoesNotTreatNegativeAnswerAsTaskDenial() {
        val outcome = UserInterventionOutcomeResolver.resolve(
            request = UserInteractionRequest(question = "是否选择张三？"),
            response = UserActionResponse(answer = "否")
        )

        assertEquals(UserInterventionOutcome.CONTINUE, outcome)
    }

    @Test
    fun ordinaryQuestionTimeoutStopsWaitingInsteadOfSilentlyContinuing() {
        val outcome = UserInterventionOutcomeResolver.resolve(
            request = UserInteractionRequest(question = "是否选择张三？"),
            response = UserActionResponse.timeout()
        )

        assertEquals(UserInterventionOutcome.TIMED_OUT, outcome)
    }
}
