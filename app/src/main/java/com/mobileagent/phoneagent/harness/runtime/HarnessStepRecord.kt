package com.mobileagent.phoneagent.harness.runtime

import com.mobileagent.phoneagent.harness.act.ExecutionResult
import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.plan.PlanDecision
import com.mobileagent.phoneagent.harness.verify.VerificationResult

enum class StepStatus {
    OBSERVATION_FAILED,
    PLANNED,
    EXECUTED,
    FINISHED,
    FAILED
}

data class HarnessStepRecord(
    val stepIndex: Int,
    val observation: Observation,
    val decision: PlanDecision?,
    val execution: ExecutionResult?,
    val verification: VerificationResult?,
    val status: StepStatus,
    val errorMessage: String? = null
)
