package com.mobileagent.phoneagent.harness.plan

import com.mobileagent.phoneagent.model.ModelCallStats

enum class PlanDecisionSource {
    LLM,
    TASK_PREPROCESSOR
}

data class PlanDecision(
    val thinking: String,
    val rawResponse: String,
    val actionJson: String,
    val finishRequested: Boolean = false,
    val source: PlanDecisionSource = PlanDecisionSource.LLM,
    val executor: String? = null,
    val taskType: String? = null,
    val confidence: Float? = null,
    val skipLlm: Boolean = false,
    val modelCallStats: ModelCallStats? = null
)
