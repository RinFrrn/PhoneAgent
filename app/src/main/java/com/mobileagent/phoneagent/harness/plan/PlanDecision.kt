package com.mobileagent.phoneagent.harness.plan

data class PlanDecision(
    val thinking: String,
    val rawResponse: String,
    val actionJson: String,
    val finishRequested: Boolean = false
)
