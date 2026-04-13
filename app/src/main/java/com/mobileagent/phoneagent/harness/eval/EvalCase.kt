package com.mobileagent.phoneagent.harness.eval

data class EvalCase(
    val id: String,
    val name: String,
    val taskIdContains: String? = null,
    val taskGoalContains: String? = null,
    val mode: String? = null,
    val expectSuccess: Boolean = true,
    val maxSteps: Int? = null,
    val outcomeContains: String? = null,
    val minVerificationPassRate: Float? = null
)

data class EvalCaseResult(
    val caseId: String,
    val caseName: String,
    val matchedSessionId: String? = null,
    val passed: Boolean,
    val reasons: List<String>
)
