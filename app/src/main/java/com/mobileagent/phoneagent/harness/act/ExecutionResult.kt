package com.mobileagent.phoneagent.harness.act

import com.mobileagent.phoneagent.harness.recover.FailureType
import com.mobileagent.phoneagent.harness.trace.TaskNote

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
    val taskNote: TaskNote? = null
)
