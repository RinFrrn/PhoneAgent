package com.mobileagent.phoneagent.harness.act

import com.mobileagent.phoneagent.action.ClipboardTrace
import com.mobileagent.phoneagent.action.UserInteractionRequest
import com.mobileagent.phoneagent.harness.recover.FailureType
import com.mobileagent.phoneagent.harness.trace.TaskNote

enum class TerminalVerificationRequirement {
    NONE,
    FINAL_OBSERVATION
}

data class ExecutionRequest(
    val actionJson: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val currentApp: String?,
    val taskGoal: String?
)

data class ExecutionResult(
    val success: Boolean,
    val shouldFinish: Boolean,
    val message: String?,
    val actionJson: String,
    val requiresTakeover: Boolean = false,
    val failureType: FailureType? = null,
    val launchTrace: AppLaunchTrace? = null,
    val humanizationTrace: ExecutionHumanizationTrace? = null,
    val taskNote: TaskNote? = null,
    val userInteractionRequest: UserInteractionRequest? = null,
    val clipboardTrace: ClipboardTrace? = null,
    val terminalVerificationRequirement: TerminalVerificationRequirement = TerminalVerificationRequirement.FINAL_OBSERVATION
)
