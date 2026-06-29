package com.mobileagent.phoneagent.harness.runtime

import android.content.Context
import android.util.Log
import com.mobileagent.phoneagent.action.ActionResult
import com.mobileagent.phoneagent.agent.AgentRuntimeState
import com.mobileagent.phoneagent.agent.AgentSessionCoordinator
import com.mobileagent.phoneagent.agent.AgentStateMachine
import com.mobileagent.phoneagent.agent.FailureTracker
import com.mobileagent.phoneagent.agent.SessionMemory
import com.mobileagent.phoneagent.agent.TaskOutcome
import com.mobileagent.phoneagent.harness.act.ActionExecutor
import com.mobileagent.phoneagent.harness.act.ExecutionRequest
import com.mobileagent.phoneagent.harness.act.ExecutionResult
import com.mobileagent.phoneagent.harness.learn.LearnedSkillRepository
import com.mobileagent.phoneagent.harness.learn.TracePathSummarizer
import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.observe.ObservationCollector
import com.mobileagent.phoneagent.harness.plan.Planner
import com.mobileagent.phoneagent.harness.plan.TaskPreprocessor
import com.mobileagent.phoneagent.harness.recover.FailureClassifier
import com.mobileagent.phoneagent.harness.recover.DefaultRecoveryPolicy
import com.mobileagent.phoneagent.harness.recover.FailureType
import com.mobileagent.phoneagent.harness.spec.TaskSpec
import com.mobileagent.phoneagent.harness.trace.StepTrace
import com.mobileagent.phoneagent.harness.trace.TaskHistoryStatus
import com.mobileagent.phoneagent.harness.trace.TraceStore
import com.mobileagent.phoneagent.harness.verify.StepVerifier
import com.mobileagent.phoneagent.harness.verify.VerificationResult
import com.mobileagent.phoneagent.model.Message
import com.mobileagent.phoneagent.skill.SkillRegistry
import com.mobileagent.phoneagent.skill.SkillExecutionAdvisor
import kotlinx.coroutines.delay

enum class RuntimePhase {
    OBSERVING,
    MODEL_GENERATING,
    EXECUTING,
    VERIFYING
}

data class RuntimeStatusUpdate(
    val status: String,
    val detail: String,
    val phase: RuntimePhase
)

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
    private val traceStore: TraceStore,
    private val failureClassifier: FailureClassifier,
    private val recoveryPolicy: DefaultRecoveryPolicy,
    private val taskPreprocessor: TaskPreprocessor = TaskPreprocessor()
) {
    private val tag = "HarnessRuntime"
    private val learnedSkillRepository = LearnedSkillRepository(context)
    private val tracePathSummarizer = TracePathSummarizer()

    companion object {
        private const val REPLAN_AFTER_INEFFECTIVE = 2
        private const val TAKEOVER_AFTER_INEFFECTIVE = 3
    }

    suspend fun run(
        taskSpec: TaskSpec,
        screenWidth: Int,
        screenHeight: Int,
        onStatusUpdate: ((RuntimeStatusUpdate) -> Unit)? = null,
        onStepRecord: ((HarnessStepRecord) -> Unit)? = null,
        onUserIntervention: ((String) -> Unit)? = null,
        onComplete: (TaskOutcome) -> Unit
    ) {
        val session = HarnessSession(taskSpec)
        val traceSessionId = traceStore.openSession(
            taskId = taskSpec.id,
            taskGoal = taskSpec.goal,
            mode = taskSpec.mode,
            modelProvider = taskSpec.modelProvider,
            modelDisplayName = taskSpec.modelDisplayName,
            modelName = taskSpec.modelName,
            modelBaseUrl = taskSpec.modelBaseUrl
        )

        try {
            while (stateMachine.isActive() && session.stepCount < taskSpec.maxSteps) {
                val stepIndex = session.nextStepIndex()
                val stepWarnings = RuntimeStepHealthMonitor.warningsForStep(stepIndex, taskSpec.maxSteps)
                Log.d(tag, "执行 Harness 步骤: $stepIndex/${taskSpec.maxSteps}")

                failureTracker.consumeReplanPrompt(taskSpec.goal)?.let(sessionMemory::add)
                failureTracker.maybeUserInterventionPrompt()?.let(sessionMemory::add)

                onStatusUpdate?.invoke(
                    RuntimeStatusUpdate(
                        status = "观察页面中",
                        detail = buildStatusDetail("正在读取当前页面状态", stepWarnings),
                        phase = RuntimePhase.OBSERVING
                    )
                )
                val observation = observationCollector.collect()
                if (observation.failureMessage != null) {
                    val record = HarnessStepRecord(
                        stepIndex = stepIndex,
                        observation = observation,
                        decision = null,
                        execution = null,
                        verification = null,
                        status = StepStatus.OBSERVATION_FAILED,
                        errorMessage = observation.failureMessage,
                        runtimeWarnings = stepWarnings
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
                            errorMessage = observation.failureMessage,
                            failureType = failureClassifier.classifyObservationFailure(observation.failureMessage),
                            runtimeWarnings = stepWarnings
                        )
                    )
                    onStepRecord?.invoke(record)
                    stateMachine.markFailed()
                    val message = observation.failureMessage
                    traceStore.closeSession(
                        traceSessionId,
                        status = TaskHistoryStatus.FAILED,
                        outcomeMessage = message,
                        failureType = failureClassifier.classifyObservationFailure(observation.failureMessage)
                    )
                    onComplete(TaskOutcome(false, message, traceSessionId))
                    return
                }

                sessionMemory.addObservation(observation.contentItems)

                onStatusUpdate?.invoke(
                    RuntimeStatusUpdate(
                        status = "规划下一步",
                        detail = "正在判断是否可直接执行，必要时再请求模型",
                        phase = RuntimePhase.MODEL_GENERATING
                    )
                )
                val decision = try {
                    val preprocessed = if (stepIndex == 1) {
                        taskPreprocessor.preprocess(taskSpec.goal)
                    } else {
                        null
                    }
                    if (preprocessed != null) {
                        preprocessed.toPlanDecision()
                    } else {
                        planner.plan(taskSpec, observation, sessionMemory)
                    }
                } catch (e: Exception) {
                    val message = "模型请求失败: ${e.message}"
                    val record = HarnessStepRecord(
                        stepIndex = stepIndex,
                        observation = observation,
                        decision = null,
                        execution = null,
                        verification = null,
                        status = StepStatus.FAILED,
                        errorMessage = message,
                        runtimeWarnings = stepWarnings
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
                            errorMessage = message,
                            failureType = failureClassifier.classifyModelFailure(message),
                            runtimeWarnings = stepWarnings
                        )
                    )
                    onStepRecord?.invoke(record)
                    stateMachine.markFailed()
                    traceStore.closeSession(
                        traceSessionId,
                        status = TaskHistoryStatus.FAILED,
                        outcomeMessage = message,
                        failureType = failureClassifier.classifyModelFailure(message)
                    )
                    onComplete(TaskOutcome(false, message, traceSessionId))
                    return
                }

                onStatusUpdate?.invoke(
                    RuntimeStatusUpdate(
                        status = "执行操作中",
                        detail = buildStatusDetail(if (decision.skipLlm) {
                            "正在执行任务预处理生成的直接操作"
                        } else {
                            "正在执行规划返回的操作"
                        }, stepWarnings),
                        phase = RuntimePhase.EXECUTING
                    )
                )
                val execution = actionExecutor.execute(
                    ExecutionRequest(
                        actionJson = decision.actionJson,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        currentApp = observation.currentApp,
                        taskGoal = taskSpec.goal
                    )
                )

                onStatusUpdate?.invoke(
                    RuntimeStatusUpdate(
                        status = "验证结果中",
                        detail = "正在检查操作是否生效",
                        phase = RuntimePhase.VERIFYING
                    )
                )
                val afterObservation = collectPostExecutionObservation(execution)
                val verification = stepVerifier.verify(
                    before = observation,
                    execution = execution,
                    after = afterObservation,
                    taskSpec = taskSpec
                )
                val stagnation = session.recordStepOutcome(
                    actionJson = decision.actionJson,
                    before = observation,
                    after = afterObservation,
                    verification = verification
                )
                val baseFailureType = execution.failureType
                    ?: failureClassifier.classifyExecutionFailure(execution, verification)
                val effectiveFailureType = if (stagnation.ineffective) {
                    FailureType.ACTION_NOT_EFFECTIVE
                } else {
                    baseFailureType
                }
                val stagnationTakeover = stagnation.ineffective &&
                    stagnation.consecutiveIneffectiveActions >= TAKEOVER_AFTER_INEFFECTIVE
                val effectiveMessage = buildResultMessage(
                    execution = execution,
                    verification = verification,
                    stagnation = stagnation
                )
                val effectiveExecution = execution.copy(
                    success = execution.success && verification.passed,
                    message = effectiveMessage,
                    requiresTakeover = execution.requiresTakeover || stagnationTakeover,
                    failureType = effectiveFailureType
                )

                failureTracker.recordActionResult(
                    decision.actionJson,
                    ActionResult(
                        success = effectiveExecution.success,
                        shouldFinish = execution.shouldFinish,
                        message = effectiveMessage,
                        requiresTakeover = effectiveExecution.requiresTakeover
                    ),
                    ineffective = stagnation.ineffective
                )

                sessionMemory.removeImageFromLastUserMessage()
                sessionMemory.addAssistantResponse(decision.rawResponse)
                if (stagnation.ineffective &&
                    stagnation.consecutiveIneffectiveActions >= REPLAN_AFTER_INEFFECTIVE
                ) {
                    sessionMemory.add(
                        Message(
                            "user",
                            buildStagnationReplanMessage(taskSpec, stagnation)
                        )
                    )
                }
                addFailureRecoveryHints(
                    observation = observation,
                    taskSpec = taskSpec,
                    decision = decision.actionJson,
                    execution = effectiveExecution
                )
                applyRecoveryDecision(taskSpec, observation, effectiveExecution)

                if (effectiveExecution.requiresTakeover && effectiveExecution.message != null) {
                    stateMachine.markWaitingForUser()
                    onUserIntervention?.invoke(effectiveExecution.message)
                    sessionMemory.addInterventionMessage(effectiveExecution.message)
                    AgentSessionCoordinator.waitForUserConfirmation(timeoutMs = 180_000)
                    stateMachine.resumeAfterUserIntervention()
                }

                val status = when {
                    execution.shouldFinish || decision.finishRequested -> StepStatus.FINISHED
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
                        errorMessage = if (status == StepStatus.FAILED) effectiveExecution.message else null,
                        failureType = if (status == StepStatus.FAILED) {
                            effectiveExecution.failureType ?: FailureType.UNKNOWN
                        } else {
                            null
                        },
                        runtimeWarnings = stepWarnings
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
                        errorMessage = if (status == StepStatus.FAILED) effectiveExecution.message else null,
                        runtimeWarnings = stepWarnings
                    )
                )

                if (execution.shouldFinish || decision.finishRequested) {
                    stateMachine.markCompleted()
                    val message = effectiveExecution.message ?: "任务完成"
                    traceStore.closeSession(
                        traceSessionId,
                        status = TaskHistoryStatus.SUCCEEDED,
                        outcomeMessage = message
                    )
                    learnFromSuccessfulTrace(traceSessionId)
                    onComplete(TaskOutcome(true, message, traceSessionId))
                    return
                }

                delay(800)
            }

            if (!stateMachine.isActive()) {
                val stoppedByUser = stateMachine.currentState() == AgentRuntimeState.STOPPED
                val message = if (stoppedByUser) "任务被停止" else "任务失败"
                traceStore.closeSession(
                    traceSessionId,
                    status = if (stoppedByUser) TaskHistoryStatus.STOPPED else TaskHistoryStatus.FAILED,
                    outcomeMessage = message,
                    failureType = if (stoppedByUser) FailureType.TASK_STOPPED else FailureType.UNKNOWN
                )
                return
            }

            stateMachine.markFailed()
            val message = recoveryPolicy.decide(
                failureType = FailureType.MAX_STEPS_EXCEEDED,
                taskSpec = taskSpec,
                observation = Observation(currentApp = taskSpec.goal, contentItems = emptyList()),
                execution = null
            ).userMessage ?: "达到最大步数仍未完成"
            traceStore.closeSession(
                traceSessionId,
                status = TaskHistoryStatus.FAILED,
                outcomeMessage = message,
                failureType = FailureType.MAX_STEPS_EXCEEDED
            )
            onComplete(TaskOutcome(false, message, traceSessionId))
        } catch (e: Exception) {
            traceStore.closeSession(
                traceSessionId,
                status = TaskHistoryStatus.FAILED,
                outcomeMessage = "运行时异常: ${e.message}",
                failureType = FailureType.UNKNOWN
            )
            throw e
        }
    }

    private fun buildStatusDetail(base: String, warnings: List<RuntimeWarning>): String {
        if (warnings.isEmpty()) {
            return base
        }
        val warningText = warnings.joinToString("；") { it.message }
        return "$base；$warningText"
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
        verification: VerificationResult,
        stagnation: StagnationResult? = null
    ): String {
        val baseMessage = execution.message ?: "动作执行完成"
        val verificationMessage = if (verification.passed) {
            "$baseMessage | 验证通过: ${verification.reason}"
        } else {
            "$baseMessage | 验证失败: ${verification.reason}"
        }
        if (stagnation == null || !stagnation.ineffective) {
            return verificationMessage
        }

        return "$verificationMessage | 停滞检测: 页面指纹未变化, " +
            "重复动作=${stagnation.repeatAction}, " +
            "连续无效次数=${stagnation.consecutiveIneffectiveActions}, " +
            "动作=${stagnation.actionFingerprint}"
    }

    private fun buildStagnationReplanMessage(
        taskSpec: TaskSpec,
        stagnation: StagnationResult
    ): String {
        val takeoverHint = if (stagnation.consecutiveIneffectiveActions >= TAKEOVER_AFTER_INEFFECTIVE) {
            "\n\n已经连续无效 ${stagnation.consecutiveIneffectiveActions} 次，请使用 Take_over 请求用户接管，或明确说明无法自动继续。"
        } else {
            ""
        }
        return "** ⚠️ 页面停滞：必须换策略 **\n\n" +
            "任务目标: ${taskSpec.goal}\n" +
            "刚才的动作已经执行，但页面指纹没有变化。\n" +
            "无效动作: ${stagnation.actionFingerprint}\n" +
            "连续无效次数: ${stagnation.consecutiveIneffectiveActions}\n\n" +
            "禁止继续重复同一动作、同一坐标或同一路径。请立即换一种策略：\n" +
            "1. 换一个更明确的控件坐标或点击文本/按钮中心\n" +
            "2. 先滑动查找更多内容\n" +
            "3. 返回上一页后重新进入\n" +
            "4. 等待页面加载后重新观察\n" +
            "5. 使用 Launch 回到目标应用入口\n" +
            "6. 如果需要验证码、登录、确认或页面无法自动处理，请使用 Take_over\n" +
            takeoverHint
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

    private fun applyRecoveryDecision(
        taskSpec: TaskSpec,
        observation: Observation,
        execution: ExecutionResult
    ) {
        val failureType = execution.failureType ?: return
        val decision = recoveryPolicy.decide(
            failureType = failureType,
            taskSpec = taskSpec,
            observation = observation,
            execution = execution
        )
        decision.userMessage?.let { sessionMemory.add(Message("user", it)) }
        if (decision.stopTask) {
            stateMachine.markFailed()
        }
    }

    private fun learnFromSuccessfulTrace(traceSessionId: String) {
        runCatching {
            val session = traceStore.loadSession(traceSessionId) ?: return
            val skill = tracePathSummarizer.summarize(session) ?: return
            val saved = learnedSkillRepository.saveFromTrace(skill)
            if (saved) {
                SkillRegistry.invalidateCache()
                Log.d(tag, "已生成动态路径技能: ${skill.id}")
            }
        }.onFailure { error ->
            Log.w(tag, "生成动态路径技能失败", error)
        }
    }
}
