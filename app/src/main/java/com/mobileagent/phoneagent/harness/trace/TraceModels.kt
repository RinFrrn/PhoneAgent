package com.mobileagent.phoneagent.harness.trace

import com.mobileagent.phoneagent.harness.act.ExecutionResult
import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.plan.PlanDecision
import com.mobileagent.phoneagent.harness.recover.FailureType
import com.mobileagent.phoneagent.harness.runtime.RuntimeStepTiming
import com.mobileagent.phoneagent.harness.runtime.RuntimeWarning
import com.mobileagent.phoneagent.harness.runtime.StepStatus
import com.mobileagent.phoneagent.harness.verify.VerificationResult

data class StepTrace(
    val stepIndex: Int,
    val timestamp: Long,
    val status: StepStatus,
    val observationBefore: Observation,
    val decision: PlanDecision?,
    val execution: ExecutionResult?,
    val observationAfter: Observation?,
    val verification: VerificationResult?,
    val errorMessage: String? = null,
    val failureType: FailureType? = null,
    val timing: RuntimeStepTiming? = null,
    val runtimeWarnings: List<RuntimeWarning> = emptyList()
)

data class SessionTrace(
    val sessionId: String,
    val taskId: String,
    val taskGoal: String,
    val mode: String,
    val modelProvider: String? = null,
    val modelDisplayName: String? = null,
    val modelName: String? = null,
    val modelBaseUrl: String? = null,
    val startedAt: Long,
    val completedAt: Long? = null,
    val success: Boolean? = null,
    val outcomeMessage: String? = null,
    val totalSteps: Int = 0,
    val steps: List<StepTrace> = emptyList(),
    val status: TaskHistoryStatus? = null,
    val dataPolicy: String? = null,
    val failureType: FailureType? = null,
    val resumedFromSessionId: String? = null,
    val resumeStrategy: TraceResumeStrategy? = null,
    val resumedPriorStepCount: Int? = null
)

enum class TraceResumeStrategy {
    FRESH_OBSERVATION
}

enum class TaskHistoryStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    STOPPED
}

data class TaskHistoryEntry(
    val sessionId: String,
    val taskId: String,
    val taskGoal: String,
    val mode: String,
    val modelProvider: String? = null,
    val modelDisplayName: String? = null,
    val modelName: String? = null,
    val modelBaseUrl: String? = null,
    val startedAt: Long,
    val completedAt: Long? = null,
    val status: TaskHistoryStatus,
    val success: Boolean? = null,
    val outcomeMessage: String? = null,
    val totalSteps: Int = 0,
    val failureType: FailureType? = null,
    val traceSessionId: String = sessionId,
    val resumedFromSessionId: String? = null,
    val resumeStrategy: TraceResumeStrategy? = null,
    val resumedPriorStepCount: Int? = null
)
