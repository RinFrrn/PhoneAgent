package com.mobileagent.phoneagent.harness.act

import com.mobileagent.phoneagent.harness.recover.FailureType

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
    val failureType: FailureType? = null
)
