package com.mobileagent.phoneagent.harness.eval

data class EvalReport(
    val generatedAt: Long,
    val totalCases: Int,
    val passedCases: Int,
    val failedCases: Int,
    val results: List<EvalCaseResult>
)
