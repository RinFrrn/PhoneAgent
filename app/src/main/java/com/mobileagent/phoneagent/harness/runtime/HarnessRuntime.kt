package com.mobileagent.phoneagent.harness.runtime

import android.content.Context
import android.util.Log
import com.mobileagent.phoneagent.action.ActionResult
import com.mobileagent.phoneagent.agent.AgentSessionCoordinator
import com.mobileagent.phoneagent.agent.AgentStateMachine
import com.mobileagent.phoneagent.agent.FailureTracker
import com.mobileagent.phoneagent.agent.SessionMemory
import com.mobileagent.phoneagent.agent.TaskOutcome
import com.mobileagent.phoneagent.harness.act.ActionExecutor
import com.mobileagent.phoneagent.harness.act.ExecutionRequest
import com.mobileagent.phoneagent.harness.act.ExecutionResult
import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.observe.ObservationCollector
import com.mobileagent.phoneagent.harness.plan.Planner
import com.mobileagent.phoneagent.harness.spec.TaskSpec
import com.mobileagent.phoneagent.harness.trace.StepTrace
import com.mobileagent.phoneagent.harness.trace.TraceStore
import com.mobileagent.phoneagent.harness.verify.StepVerifier
import com.mobileagent.phoneagent.harness.verify.VerificationResult
import com.mobileagent.phoneagent.model.Message
import com.mobileagent.phoneagent.skill.SkillExecutionAdvisor
import kotlinx.coroutines.delay

class HarnessRuntime(
    private val context: Context,
    private val observationCollector: ObservationCollector,
    private val planner: Planner,
    private val actionExecutor: ActionExecutor,
    private val sessionMemory: SessionMemory,
    private val stateMachine: AgentStateMachine,
    private val failureTracker: FailureTracker,
    private val skillExecutionAdvisor: SkillExecutionAdvisor,
    private val stepVerifier: StepVerifier,
    private val traceStore: TraceStore
) {
    private val tag = "HarnessRuntime"

    suspend fun run(
        taskSpec: TaskSpec,
        screenWidth: Int,
        screenHeight: Int,
        onStepRecord: ((HarnessStepRecord) -> Unit)? = null,
        onUserIntervention: ((String) -> Unit)? = null,
        onComplete: (TaskOutcome) -> Unit
    ) {
        val session = HarnessSession(taskSpec)
        val traceSessionId = traceStore.openSession(
            taskId = taskSpec.id,
            taskGoal = taskSpec.goal,
            mode = taskSpec.mode
        )

        try {
            while (stateMachine.isActive() && session.stepCount < taskSpec.maxSteps) {
                val stepIndex = session.nextStepIndex()
                Log.d(tag, "执行 Harness 步骤: $stepIndex/${taskSpec.maxSteps}")

                failureTracker.consumeReplanPrompt(taskSpec.goal)?.let(sessionMemory::add)
                failureTracker.maybeUserInterventionPrompt()?.let(sessionMemory::add)

                val observation = observationCollector.collect()
                if (observation.failureMessage != null) {
                    val record = HarnessStepRecord(
                        stepIndex = stepIndex,
                        observation = observation,
                        decision = null,
                        execution = null,
                        verification = null,
                        status = StepStatus.OBSERVATION_FAILED,
                        errorMessage = observation.failureMessage
                    )
                    traceStore.appendStep(
                        traceSessionId,
                        StepTrace(
                            stepIndex = stepIndex,
                            timestamp = System.currentTimeMillis(),
                            status = StepStatus.OBSERVATION_FAILED,
                            observationBefore = observation,
                            decision = null,
                            execution = null,
                            observationAfter = null,
                            verification = null,
                            errorMessage = observation.failureMessage
                        )
                    )
                    onStepRecord?.invoke(record)
                    stateMachine.markFailed()
                    val message = observation.failureMessage
                    traceStore.closeSession(traceSessionId, success = false, outcomeMessage = message)
                    onComplete(TaskOutcome(false, message))
                    return
                }

                sessionMemory.addObservation(observation.contentItems)

                val decision = try {
                    planner.plan(taskSpec, observation, sessionMemory)
                } catch (e: Exception) {
                    val message = "模型请求失败: ${e.message}"
                    val record = HarnessStepRecord(
                        stepIndex = stepIndex,
                        observation = observation,
                        decision = null,
                        execution = null,
                        verification = null,
                        status = StepStatus.FAILED,
                        errorMessage = message
                    )
                    traceStore.appendStep(
                        traceSessionId,
                        StepTrace(
                            stepIndex = stepIndex,
                            timestamp = System.currentTimeMillis(),
                            status = StepStatus.FAILED,
                            observationBefore = observation,
                            decision = null,
                            execution = null,
                            observationAfter = null,
                            verification = null,
                            errorMessage = message
                        )
                    )
                    onStepRecord?.invoke(record)
                    stateMachine.markFailed()
                    traceStore.closeSession(traceSessionId, success = false, outcomeMessage = message)
                    onComplete(TaskOutcome(false, message))
                    return
                }

                val execution = actionExecutor.execute(
                    ExecutionRequest(
                        actionJson = decision.actionJson,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        currentApp = observation.currentApp,
                        taskGoal = taskSpec.goal
                    )
                )

                val afterObservation = collectPostExecutionObservation(execution)
                val verification = stepVerifier.verify(
                    before = observation,
                    execution = execution,
                    after = afterObservation,
                    taskSpec = taskSpec
                )

                failureTracker.recordActionResult(
                    decision.actionJson,
                    ActionResult(
                        success = execution.success && verification.passed,
                        shouldFinish = execution.shouldFinish,
                        message = buildResultMessage(execution, verification),
                        requiresTakeover = execution.requiresTakeover
                    )
                )

                if (execution.requiresTakeover && execution.message != null) {
                    stateMachine.markWaitingForUser()
                    onUserIntervention?.invoke(execution.message)
                    sessionMemory.addInterventionMessage(execution.message)
                    AgentSessionCoordinator.waitForUserConfirmation(timeoutMs = 180_000)
                    stateMachine.resumeAfterUserIntervention()
                }

                sessionMemory.removeImageFromLastUserMessage()
                sessionMemory.addAssistantResponse(decision.rawResponse)
                val effectiveExecution = execution.copy(
                    success = execution.success && verification.passed,
                    message = buildResultMessage(execution, verification)
                )
                addFailureRecoveryHints(
                    observation = observation,
                    taskSpec = taskSpec,
                    decision = decision.actionJson,
                    execution = effectiveExecution
                )

                val status = when {
                    execution.shouldFinish -> StepStatus.FINISHED
                    effectiveExecution.success -> StepStatus.EXECUTED
                    else -> StepStatus.FAILED
                }

                traceStore.appendStep(
                    traceSessionId,
                    StepTrace(
                        stepIndex = stepIndex,
                        timestamp = System.currentTimeMillis(),
                        status = status,
                        observationBefore = observation,
                        decision = decision,
                        execution = effectiveExecution,
                        observationAfter = afterObservation,
                        verification = verification,
                        errorMessage = if (status == StepStatus.FAILED) effectiveExecution.message else null
                    )
                )

                onStepRecord?.invoke(
                    HarnessStepRecord(
                        stepIndex = stepIndex,
                        observation = observation,
                        decision = decision,
                        execution = effectiveExecution,
                        verification = verification,
                        status = status,
                        errorMessage = if (status == StepStatus.FAILED) effectiveExecution.message else null
                    )
                )

                if (execution.shouldFinish || decision.finishRequested) {
                    stateMachine.markCompleted()
                    val message = effectiveExecution.message ?: "任务完成"
                    traceStore.closeSession(traceSessionId, success = true, outcomeMessage = message)
                    onComplete(TaskOutcome(true, message))
                    return
                }

                delay(800)
            }

            if (!stateMachine.isActive()) {
                traceStore.closeSession(traceSessionId, success = false, outcomeMessage = "任务被停止")
                return
            }

            stateMachine.markFailed()
            traceStore.closeSession(traceSessionId, success = false, outcomeMessage = "达到最大步数仍未完成")
            onComplete(TaskOutcome(false, "达到最大步数仍未完成"))
        } catch (e: Exception) {
            traceStore.closeSession(traceSessionId, success = false, outcomeMessage = "运行时异常: ${e.message}")
            throw e
        }
    }

    private suspend fun collectPostExecutionObservation(execution: ExecutionResult): Observation? {
        if (execution.shouldFinish || execution.requiresTakeover || !execution.success) {
            return null
        }
        return try {
            observationCollector.collect()
        } catch (_: Exception) {
            null
        }
    }

    private fun buildResultMessage(
        execution: ExecutionResult,
        verification: VerificationResult
    ): String {
        val baseMessage = execution.message ?: "动作执行完成"
        return if (verification.passed) {
            "$baseMessage | 验证通过: ${verification.reason}"
        } else {
            "$baseMessage | 验证失败: ${verification.reason}"
        }
    }

    private fun addFailureRecoveryHints(
        observation: Observation,
        taskSpec: TaskSpec,
        decision: String,
        execution: ExecutionResult
    ) {
        if (execution.success || execution.message == null) {
            return
        }

        sessionMemory.addFailureFeedback(execution.message)
        val recoveryMessage = skillExecutionAdvisor.buildFailureRecoveryMessage(
            context = context,
            currentApp = observation.currentApp,
            task = taskSpec.goal,
            actionJson = decision,
            actionResult = ActionResult(
                success = execution.success,
                shouldFinish = execution.shouldFinish,
                message = execution.message,
                requiresTakeover = execution.requiresTakeover
            )
        ) ?: return

        sessionMemory.add(Message("user", recoveryMessage))
    }
}
