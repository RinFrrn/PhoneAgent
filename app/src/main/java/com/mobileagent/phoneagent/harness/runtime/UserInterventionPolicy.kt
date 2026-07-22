package com.mobileagent.phoneagent.harness.runtime

import com.mobileagent.phoneagent.action.UserInteractionKind
import com.mobileagent.phoneagent.action.UserInteractionRequest
import com.mobileagent.phoneagent.agent.UserActionResponse

enum class UserInterventionOutcome {
    CONTINUE,
    DENIED,
    TIMED_OUT
}

object UserInterventionOutcomeResolver {
    fun resolve(
        request: UserInteractionRequest?,
        response: UserActionResponse
    ): UserInterventionOutcome {
        if (response.timedOut) {
            return UserInterventionOutcome.TIMED_OUT
        }
        if (request?.kind != UserInteractionKind.SENSITIVE_CONFIRMATION) {
            return UserInterventionOutcome.CONTINUE
        }
        return if (response.answer.isExplicitConfirmation()) {
            UserInterventionOutcome.CONTINUE
        } else {
            UserInterventionOutcome.DENIED
        }
    }

    private fun String.isExplicitConfirmation(): Boolean {
        val normalized = trim()
            .lowercase()
            .replace(Regex("[\\s，,。.!！?？]+"), "")
        return normalized in explicitConfirmationAnswers
    }

    private val explicitConfirmationAnswers = setOf(
        "确认继续",
        "确认",
        "继续",
        "同意继续",
        "允许继续",
        "yes",
        "y",
        "confirm",
        "continue"
    )
}
