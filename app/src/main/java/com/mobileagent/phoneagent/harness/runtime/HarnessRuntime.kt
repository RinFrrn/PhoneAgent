package com.mobileagent.phoneagent.harness.runtime

import android.content.Context
import android.os.SystemClock
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
import com.mobileagent.phoneagent.harness.act.TerminalVerificationRequirement
import com.mobileagent.phoneagent.harness.learn.LearnedSkillRepository
import com.mobileagent.phoneagent.harness.learn.TracePathSummarizer
import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.observe.ObservationCollector
import com.mobileagent.phoneagent.harness.plan.Planner
import com.mobileagent.phoneagent.harness.plan.TaskPreprocessor
import com.mobileagent.phoneagent.harness.recover.FailureClassifier
import com.mobileagent.phoneagent.harness.recover.DefaultRecoveryPolicy
import com.mobileagent.phoneagent.harness.recover.FailureType
import com.mobileagent.phoneagent.harness.recover.RecoveryContext
import com.mobileagent.phoneagent.harness.recover.RecoveryDecision
import com.mobileagent.phoneagent.harness.spec.TaskSpec
import com.mobileagent.phoneagent.harness.trace.StepTrace
import com.mobileagent.phoneagent.harness.trace.TaskHistoryStatus
import com.mobileagent.phoneagent.harness.trace.TraceStore
import com.mobileagent.phoneagent.harness.trace.TraceResumeStrategy
import com.mobileagent.phoneagent.harness.verify.StepVerifier
import com.mobileagent.phoneagent.harness.verify.VerificationResult
import com.mobileagent.phoneagent.model.Message
import com.mobileagent.phoneagent.skill.SkillRegistry
import com.mobileagent.phoneagent.skill.SkillExecutionAdvisor
import kotlinx.coroutines.CancellationException
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
        taskSpec.resumeContext?.let { resumeContext ->
            sessionMemory.addResumeContext(resumeContext)
        }
        val traceSessionId = traceStore.openSession(
            taskId = taskSpec.id,
            taskGoal = taskSpec.goal,
            mode = taskSpec.mode,
            modelProvider = taskSpec.modelProvider,
            modelDisplayName = taskSpec.modelDisplayName,
            modelName = taskSpec.modelName,
            modelBaseUrl = taskSpec.modelBaseUrl,
            resumedFromSessionId = taskSpec.resumeContext?.sourceSessionId,
            resumeStrategy = taskSpec.resumeContext?.let { TraceResumeStrategy.FRESH_OBSERVATION },
            resumedPriorStepCount = taskSpec.resumeContext?.completedStepCount
        )

        try {
            while (stateMachine.isActive() && session.stepCount < taskSpec.maxSteps) {
                val stepIndex = session.nextStepIndex()
                val stepStartedAt = SystemClock.elapsedRealtime()
                var observationMs = 0L
                var planningMs = 0L
                var executionMs = 0L
                var verificationMs = 0L
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
                val observationStartedAt = SystemClock.elapsedRealtime()
                val observation = try {
                    observationCollector.collect()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Observation(
                        currentApp = null,
                        contentItems = emptyList(),
                        failureMessage = "页面观察异常: ${e.message ?: e::class.java.simpleName}"
                    )
                }
                observationMs = elapsedSince(observationStartedAt)
                if (observation.failureMessage != null) {
                    val failureType = failureClassifier.classifyObservationFailure(observation.failureMessage)
                    val recoveryContext = RecoveryContext(session.recordRecoveryFailure(failureType))
                    val recoveryDecision = recoveryPolicy.decide(
                        failureType = failureType,
                        taskSpec = taskSpec,
                        observation = observation,
                        execution = null,
                        context = recoveryContext
                    )
                    val recoveryTrace = recoveryDecision.toTrace(failureType, recoveryContext)
                    val timing = RuntimeStepTiming(
                        totalMs = elapsedSince(stepStartedAt),
                        observationMs = observationMs
                    )
                    val runtimeWarnings = stepWarnings + timing.warnings()
                    val record = HarnessStepRecord(
                        stepIndex = stepIndex,
                        observation = observation,
                        decision = null,
                        execution = null,
                        verification = null,
                        status = StepStatus.OBSERVATION_FAILED,
                        errorMessage = observation.failureMessage,
                        recovery = recoveryTrace,
                        timing = timing,
                        runtimeWarnings = runtimeWarnings
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
                            failureType = failureType,
                            recovery = recoveryTrace,
                            timing = timing,
                            runtimeWarnings = runtimeWarnings
                        )
                    )
                    onStepRecord?.invoke(record)
                    if (recoveryDecision.shouldRetry) {
                        onStatusUpdate?.invoke(
                            RuntimeStatusUpdate(
                                status = "正在恢复观察",
                                detail = "${recoveryDecision.userMessage}（${recoveryContext.attempt}/${recoveryDecision.maxAttempts}）",
                                phase = RuntimePhase.OBSERVING
                            )
                        )
                        delay(recoveryDecision.delayMs)
                        continue
                    }
                    stateMachine.markFailed()
                    val message = recoveryDecision.userMessage ?: observation.failureMessage
                    traceStore.closeSession(
                        traceSessionId,
                        status = TaskHistoryStatus.FAILED,
                        outcomeMessage = message,
                        failureType = failureType
                    )
                    onComplete(TaskOutcome(false, message, traceSessionId))
                    return
                }
                session.resetRecoveryFailures(FailureType.OBSERVATION_FAILED)

                sessionMemory.addObservation(observation.contentItems)

                onStatusUpdate?.invoke(
                    RuntimeStatusUpdate(
                        status = "规划下一步",
                        detail = "正在判断是否可直接执行，必要时再请求模型",
                        phase = RuntimePhase.MODEL_GENERATING
                    )
                )
                val planningStartedAt = SystemClock.elapsedRealtime()
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    planningMs = elapsedSince(planningStartedAt)
                    val timing = RuntimeStepTiming(
                        totalMs = elapsedSince(stepStartedAt),
                        observationMs = observationMs,
                        planningMs = planningMs
                    )
                    val runtimeWarnings = stepWarnings + timing.warnings()
                    val message = "模型请求失败: ${e.message}"
                    val failureType = failureClassifier.classifyModelFailure(message)
                    val recoveryContext = RecoveryContext(session.recordRecoveryFailure(failureType))
                    val recoveryDecision = recoveryPolicy.decide(
                        failureType = failureType,
                        taskSpec = taskSpec,
                        observation = observation,
                        execution = null,
                        context = recoveryContext
                    )
                    val recoveryTrace = recoveryDecision.toTrace(failureType, recoveryContext)
                    val record = HarnessStepRecord(
                        stepIndex = stepIndex,
                        observation = observation,
                        decision = null,
                        execution = null,
                        verification = null,
                        status = StepStatus.FAILED,
                        errorMessage = message,
                        recovery = recoveryTrace,
                        timing = timing,
                        runtimeWarnings = runtimeWarnings
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
                            failureType = failureType,
                            recovery = recoveryTrace,
                            timing = timing,
                            runtimeWarnings = runtimeWarnings
                        )
                    )
                    onStepRecord?.invoke(record)
                    if (recoveryDecision.shouldRetry) {
                        sessionMemory.removeImageFromLastUserMessage()
                        onStatusUpdate?.invoke(
                            RuntimeStatusUpdate(
                                status = "正在恢复模型请求",
                                detail = "${recoveryDecision.userMessage}（${recoveryContext.attempt}/${recoveryDecision.maxAttempts}）",
                                phase = RuntimePhase.MODEL_GENERATING
                            )
                        )
                        delay(recoveryDecision.delayMs)
                        continue
                    }
                    stateMachine.markFailed()
                    val outcomeMessage = recoveryDecision.userMessage ?: message
                    traceStore.closeSession(
                        traceSessionId,
                        status = TaskHistoryStatus.FAILED,
                        outcomeMessage = outcomeMessage,
                        failureType = failureType
                    )
                    onComplete(TaskOutcome(false, outcomeMessage, traceSessionId))
                    return
                }
                planningMs = elapsedSince(planningStartedAt)
                session.resetRecoveryFailures(FailureType.MODEL_REQUEST_FAILED)

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
                val executionStartedAt = SystemClock.elapsedRealtime()
                val execution = actionExecutor.execute(
                    ExecutionRequest(
                        actionJson = decision.actionJson,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        currentApp = observation.currentApp,
                        taskGoal = taskSpec.goal
                    )
                )
                executionMs = elapsedSince(executionStartedAt)

                onStatusUpdate?.invoke(
                    RuntimeStatusUpdate(
                        status = "验证结果中",
                        detail = "正在检查操作是否生效",
                        phase = RuntimePhase.VERIFYING
                    )
                )
                val verificationStartedAt = SystemClock.elapsedRealtime()
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
                val executionSucceeded = execution.success && verification.passed
                val effectiveFailureType = when {
                    stagnation.ineffective -> FailureType.ACTION_NOT_EFFECTIVE
                    executionSucceeded -> null
                    execution.failureType != null -> execution.failureType
                    else -> failureClassifier.classifyExecutionFailure(execution, verification)
                }
                val stagnationTakeover = stagnation.ineffective &&
                    stagnation.consecutiveIneffectiveActions >= TAKEOVER_AFTER_INEFFECTIVE
                val effectiveMessage = buildResultMessage(
                    execution = execution,
                    verification = verification,
                    stagnation = stagnation
                )
                var effectiveExecution = execution.copy(
                    success = executionSucceeded,
                    message = effectiveMessage,
                    requiresTakeover = execution.requiresTakeover || stagnationTakeover,
                    failureType = effectiveFailureType
                )
                val recoveryContext = effectiveFailureType?.let {
                    RecoveryContext(session.recordRecoveryFailure(it))
                }
                if (effectiveFailureType == null) {
                    session.resetExecutionRecoveryFailures()
                }
                val recoveryDecision = applyRecoveryDecision(
                    taskSpec = taskSpec,
                    observation = observation,
                    execution = effectiveExecution,
                    recoveryContext = recoveryContext
                )
                val recoveryTrace = if (effectiveFailureType != null && recoveryContext != null) {
                    recoveryDecision.toTrace(effectiveFailureType, recoveryContext)
                } else {
                    null
                }
                if (recoveryDecision.requiresUserTakeover || recoveryDecision.userMessage != null) {
                    effectiveExecution = effectiveExecution.copy(
                        requiresTakeover = effectiveExecution.requiresTakeover || recoveryDecision.requiresUserTakeover,
                        message = recoveryDecision.userMessage ?: effectiveExecution.message,
                        userInteractionRequest = recoveryDecision.userInteractionRequest
                            ?: effectiveExecution.userInteractionRequest
                    )
                }

                failureTracker.recordActionResult(
                    decision.actionJson,
                    ActionResult(
                        success = effectiveExecution.success,
                        shouldFinish = execution.shouldFinish,
                        message = effectiveExecution.message,
                        requiresTakeover = effectiveExecution.requiresTakeover
                    ),
                    ineffective = stagnation.ineffective
                )

                sessionMemory.removeImageFromLastUserMessage()
                sessionMemory.addAssistantResponse(decision.rawResponse)
                if (execution.clipboardTrace != null && execution.message?.isNotBlank() == true) {
                    sessionMemory.add(
                        Message(
                            "user",
                            "** 📋 上一步剪贴板结果 **\n" +
                                "${execution.message}\n\n" +
                                "请在后续步骤中使用该内容；如果内容是验证码或链接，优先完成用户目标。"
                        )
                    )
                }
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
                verificationMs = elapsedSince(verificationStartedAt)
                val timing = RuntimeStepTiming(
                    totalMs = elapsedSince(stepStartedAt),
                    observationMs = observationMs,
                    planningMs = planningMs,
                    executionMs = executionMs,
                    verificationMs = verificationMs
                )
                val runtimeWarnings = stepWarnings + timing.warnings()

                var interventionFailureType: FailureType? = null
                val takeoverMessage = effectiveExecution.message
                if (effectiveExecution.requiresTakeover && takeoverMessage != null) {
                    stateMachine.markWaitingForUser()
                    onUserIntervention?.invoke(takeoverMessage)
                    val userResponse = AgentSessionCoordinator.waitForUserConfirmation(timeoutMs = 180_000)
                    when (UserInterventionOutcomeResolver.resolve(
                        request = effectiveExecution.userInteractionRequest,
                        response = userResponse
                    )) {
                        UserInterventionOutcome.CONTINUE -> {
                            sessionMemory.addInterventionMessage(
                                message = takeoverMessage,
                                response = userResponse
                            )
                        }
                        UserInterventionOutcome.DENIED -> {
                            interventionFailureType = FailureType.USER_DENIED
                            effectiveExecution = effectiveExecution.copy(
                                success = false,
                                requiresTakeover = false,
                                failureType = interventionFailureType,
                                message = "未收到明确的“确认继续”，敏感任务已取消。"
                            )
                        }
                        UserInterventionOutcome.TIMED_OUT -> {
                            interventionFailureType = FailureType.USER_INTERVENTION_TIMEOUT
                            effectiveExecution = effectiveExecution.copy(
                                success = false,
                                requiresTakeover = false,
                                failureType = interventionFailureType,
                                message = "等待用户输入超时，任务已停止。"
                            )
                        }
                    }
                    stateMachine.resumeAfterUserIntervention()
                }

                val terminalRequested = interventionFailureType != null ||
                    recoveryDecision.stopTask ||
                    execution.shouldFinish ||
                    decision.finishRequested
                val terminalDecision = RuntimeTerminalOutcome.decide(
                    terminalRequested = terminalRequested,
                    execution = effectiveExecution
                )
                val status = when {
                    terminalDecision != null -> terminalDecision.stepStatus
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
                        recovery = recoveryTrace,
                        timing = timing,
                        runtimeWarnings = runtimeWarnings
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
                        recovery = recoveryTrace,
                        timing = timing,
                        runtimeWarnings = runtimeWarnings
                    )
                )

                if (terminalDecision != null) {
                    val message = effectiveExecution.message ?: "任务完成"
                    if (terminalDecision.success) {
                        stateMachine.markCompleted()
                    } else {
                        stateMachine.markFailed()
                    }
                    traceStore.closeSession(
                        traceSessionId,
                        status = terminalDecision.historyStatus,
                        outcomeMessage = message,
                        failureType = terminalDecision.failureType
                    )
                    if (terminalDecision.success) {
                        learnFromSuccessfulTrace(traceSessionId)
                    }
                    onComplete(TaskOutcome(terminalDecision.success, message, traceSessionId))
                    return
                }

                delay(800)
            }

            if (!stateMachine.isActive()) {
                val closure = RuntimeTerminalOutcome.forInactiveState(stateMachine.currentState())
                traceStore.closeSession(
                    traceSessionId,
                    status = closure.historyStatus,
                    outcomeMessage = closure.message,
                    failureType = closure.failureType
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
        } catch (e: CancellationException) {
            val closure = RuntimeTerminalOutcome.forInactiveState(stateMachine.currentState())
            traceStore.closeSession(
                traceSessionId,
                status = closure.historyStatus,
                outcomeMessage = closure.message,
                failureType = closure.failureType
            )
            throw e
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

    private fun elapsedSince(startedAt: Long): Long {
        return (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
    }

    private suspend fun collectPostExecutionObservation(execution: ExecutionResult): Observation? {
        val terminalObservationNotRequired = execution.shouldFinish &&
            execution.terminalVerificationRequirement == TerminalVerificationRequirement.NONE
        if (terminalObservationNotRequired ||
            execution.requiresTakeover ||
            execution.clipboardTrace != null ||
            !execution.success
        ) {
            return null
        }
        return try {
            observationCollector.collect()
        } catch (e: CancellationException) {
            throw e
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
        execution: ExecutionResult,
        recoveryContext: RecoveryContext?
    ): RecoveryDecision {
        val failureType = execution.failureType ?: return RecoveryDecision()
        val context = recoveryContext ?: return RecoveryDecision()
        val decision = recoveryPolicy.decide(
            failureType = failureType,
            taskSpec = taskSpec,
            observation = observation,
            execution = execution,
            context = context
        )
        decision.userMessage?.let { sessionMemory.add(Message("user", it)) }
        if (decision.stopTask) {
            stateMachine.markFailed()
        }
        return decision
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
