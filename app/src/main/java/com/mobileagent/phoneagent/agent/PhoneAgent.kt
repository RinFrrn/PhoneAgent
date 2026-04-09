/**
 * Phone Agent - 核心智能体类
 * 
 * 项目地址: https://github.com/MR-MaoJiu/PhoneAgent
 * 
 * 负责协调 AI 模型、截图、操作执行等
 */
package com.mobileagent.phoneagent.agent

import android.content.Context
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.util.Log
import com.mobileagent.phoneagent.action.ActionHandler
import com.mobileagent.phoneagent.action.ActionResult
import com.mobileagent.phoneagent.model.Message
import com.mobileagent.phoneagent.model.ModelClient
import com.mobileagent.phoneagent.service.PhoneAgentAccessibilityService
import com.mobileagent.phoneagent.skill.SkillActionInterceptor
import com.mobileagent.phoneagent.skill.SkillExecutionAdvisor
import com.mobileagent.phoneagent.skill.SkillPromptAugmentor
import com.mobileagent.phoneagent.utils.ScreenshotManager
import kotlinx.coroutines.*

/**
 * 运行模式枚举
 */
enum class Mode {
    VISION,          // 视觉模式：通过截图上传图片
    ACCESSIBILITY,   // 无障碍模式：通过无障碍服务获取屏幕内容
    HYBRID          // 混合模式：结合视觉模式和无障碍模式
}

/**
 * Phone Agent - 核心智能体类
 * 负责协调 AI 模型、截图、操作执行等
 */
class PhoneAgent(
    private val context: Context,
    private val modelClient: ModelClient,
    private val accessibilityService: PhoneAgentAccessibilityService,
    private val mediaProjection: MediaProjection?,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val maxSteps: Int = Int.MAX_VALUE, // 移除最大步数限制，只有任务完成才停止
    private val systemPrompt: String,
    private val mode: Mode = Mode.VISION, // 运行模式，默认视觉模式
    private val onStepCallback: ((StepResult) -> Unit)? = null,
    private val onUserInterventionCallback: ((String) -> Unit)? = null
) {
    private val TAG = "PhoneAgent"
    private val actionHandler = ActionHandler(accessibilityService)
    private val sessionMemory = SessionMemory(modelClient, mode)
    private val responseActionParser = ResponseActionParser()
    private val screenObserver = ScreenObserver(mode, accessibilityService) {
        captureScreenshot()
    }
    private val stateMachine = AgentStateMachine()
    private val failureTracker = FailureTracker()
    private val skillActionInterceptor = SkillActionInterceptor()
    private val skillExecutionAdvisor = SkillExecutionAdvisor()
    private val skillPromptAugmentor = SkillPromptAugmentor()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var stepCount = 0
    private var screenshotManager: ScreenshotManager? = null
    private var currentTask: String? = null // 保存当前任务描述

    /**
     * 运行任务
     */
    fun run(task: String, onComplete: (String) -> Unit) {
        if (isTaskRunning()) {
            Log.w(TAG, "⚠️ Agent 已在运行中，忽略重复请求")
            return
        }

        Log.d(TAG, "========================================")
        Log.d(TAG, "🚀 开始执行任务")
        Log.d(TAG, "任务: $task")
        Log.d(TAG, "屏幕尺寸: ${screenWidth}x${screenHeight}")
        Log.d(TAG, "========================================")

        stateMachine.start()
        sessionMemory.clear()
        stepCount = 0
        failureTracker.reset()
        currentTask = task

        scope.launch {
            try {
                // 根据模式初始化 ScreenshotManager（视觉模式和混合模式需要）
                if (mode == Mode.VISION || mode == Mode.HYBRID) {
                    if (mediaProjection != null) {
                        try {
                            withContext(Dispatchers.Main) {
                                val density = context.resources.displayMetrics.densityDpi
                                screenshotManager = ScreenshotManager(mediaProjection, screenWidth, screenHeight, density)
                                screenshotManager?.initialize()
                                Log.d(TAG, "✅ ScreenshotManager 已初始化（模式: $mode）")
                            }
                        } catch (e: SecurityException) {
                            Log.e(TAG, "❌ MediaProjection 已过期或无效", e)
                            stateMachine.markFailed()
                            onComplete("MediaProjection 已过期，请重新授权屏幕录制权限后重试")
                            return@launch
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ 初始化 ScreenshotManager 失败", e)
                            stateMachine.markFailed()
                            onComplete("初始化截图管理器失败: ${e.message}")
                            return@launch
                        }
                    } else {
                        Log.e(TAG, "❌ MediaProjection 为 null，无法初始化 ScreenshotManager（模式: $mode）")
                        stateMachine.markFailed()
                        onComplete("MediaProjection 未初始化，请先授权屏幕录制权限")
                        return@launch
                    }
                } else {
                    Log.d(TAG, "✅ 无障碍模式，无需初始化 ScreenshotManager")
                }
                
                // 初始化系统提示词
                sessionMemory.addSystemMessage(systemPrompt)
                Log.d(TAG, "系统提示词已添加")

                // 执行第一步
                Log.d(TAG, "执行第一步...")
                val firstResult = executeStep(task, isFirst = true)
                if (firstResult.finished) {
                    Log.d(TAG, "第一步即完成任务")
                    stateMachine.markCompleted()
                    onComplete(firstResult.message ?: "任务完成")
                    return@launch
                }
                while (isTaskRunning()) {
                    Log.d(TAG, "========================================")
                    Log.d(TAG, "🔄 循环执行 - 当前步数: $stepCount")
                    Log.d(TAG, "任务目标: $currentTask")
                    Log.d(TAG, "连续失败次数: ${failureTracker.consecutiveFailures}")
                    Log.d(TAG, "运行状态: ${stateMachine.currentState()}")
                    Log.d(TAG, "========================================")
                    
                    failureTracker.consumeReplanPrompt(currentTask)?.let { planningHint ->
                        Log.w(TAG, "⚠️ 连续失败后，添加重新规划提示")
                        sessionMemory.add(planningHint)
                    }
                    
                    failureTracker.maybeUserInterventionPrompt()?.let { userInterventionHint ->
                        Log.w(TAG, "⚠️ 连续失败过多，添加用户介入提示")
                        sessionMemory.add(userInterventionHint)
                    }
                    
                    val result = executeStep(isFirst = false)
                    
                    Log.d(TAG, "========================================")
                    Log.d(TAG, "📊 步骤执行结果")
                    Log.d(TAG, "成功: ${result.success}")
                    Log.d(TAG, "完成: ${result.finished}")
                    Log.d(TAG, "思考: ${result.thinking.take(100)}...")
                    Log.d(TAG, "操作: ${result.action.take(100)}...")
                    if (result.message != null) {
                        Log.d(TAG, "消息: ${result.message}")
                    }
                    Log.d(TAG, "连续失败次数: ${failureTracker.consecutiveFailures}")
                    Log.d(TAG, "========================================")
                    
                    // 只有明确使用 finish() 时才停止，否则继续执行
                    if (result.finished) {
                        val isRealFinish = stateMachine.shouldFinishLoop(result)
                        if (isRealFinish) {
                            Log.d(TAG, "✅ 任务在步骤 $stepCount 完成（AI明确使用finish）")
                            stateMachine.markCompleted()
                            onComplete(result.message ?: "任务完成")
                            return@launch
                        } else {
                            Log.w(TAG, "⚠️ 步骤标记为完成，但可能是错误导致的，继续执行")
                            // 继续执行，不停止
                        }
                    }
                    
                    // 即使操作失败，也继续执行（让AI尝试不同方法）
                    // 只有在达到最大步数或明确完成时才停止
                    
                    // 短暂延迟，避免过快执行，给UI和系统一些时间
                    kotlinx.coroutines.delay(800)
                }

                // 移除最大步数限制检查，只有任务完成才停止
            } catch (e: Exception) {
                Log.e(TAG, "❌ 执行任务失败", e)
                e.printStackTrace()
                stateMachine.markFailed()
                onComplete("任务执行失败: ${e.message}")
            } finally {
                // 清理资源
                screenshotManager?.cleanup()
                screenshotManager = null
                Log.d(TAG, "✅ 资源已清理")
            }
        }
    }

    /**
     * 停止任务
     */
    fun stop() {
        stateMachine.stop()
        screenshotManager?.cleanup()
        screenshotManager = null
        scope.cancel()
        Log.d(TAG, "任务已停止，资源已清理")
    }

    /**
     * 更新任务（允许在任务执行中更新）
     */
    fun updateTask(newTask: String) {
        if (isTaskRunning()) {
            Log.d(TAG, "更新任务: $newTask")
            val oldTask = currentTask
            currentTask = newTask
            sessionMemory.addTaskUpdate(oldTask, newTask)
        }
    }

    /**
     * 检查任务是否正在运行（公开属性）
     */
    fun isTaskRunning(): Boolean {
        return stateMachine.isActive()
    }

    /**
     * 执行单步
     */
    private suspend fun executeStep(
        userPrompt: String? = null,
        isFirst: Boolean = false
    ): StepResult = withContext(Dispatchers.IO) {
        stepCount++
        Log.d(TAG, "========================================")
        Log.d(TAG, "📸 步骤 $stepCount: 开始执行")
        if (isFirst) {
            Log.d(TAG, "任务: $userPrompt")
        }

        val observation = screenObserver.observe()
        val currentApp = observation.currentApp
        Log.d(TAG, "当前应用: $currentApp")
        Log.d(TAG, "任务描述: $userPrompt")
        Log.d(TAG, "已执行步骤: $stepCount")
        
        if (observation.failureMessage != null) {
            return@withContext StepResult(
                success = false,
                finished = mode == Mode.VISION,
                thinking = observation.failureMessage,
                action = "",
                message = observation.failureMessage
            )
        }

        sessionMemory.addObservation(observation.contentItems)
        
        val messagesToSend = skillPromptAugmentor.augment(
            context = context,
            messages = sessionMemory.messagesForRequest(),
            currentApp = currentApp,
            task = currentTask ?: userPrompt
        )
        val contextSize = sessionMemory.calculateCurrentContextSize()
        Log.d(TAG, "消息已添加到上下文，总消息数: ${sessionMemory.currentMessageCount()}，发送消息数: ${messagesToSend.size}")
        Log.d(TAG, "上下文大小: ${contextSize / 1024}KB")

        // 调用模型
        Log.d(TAG, "🤖 调用 AI 模型...")
        val modelResponse = try {
            modelClient.request(messagesToSend)
        } catch (e: Exception) {
            Log.e(TAG, "❌ 模型请求失败", e)
            Log.e(TAG, "错误详情: ${e.message}")
            e.printStackTrace()
            return@withContext StepResult(
                success = false,
                finished = true,
                thinking = "模型请求失败: ${e.message}",
                action = "",
                message = "模型请求失败: ${e.message}"
            )
        }
        Log.d(TAG, "✅ 模型响应接收成功")
        Log.d(TAG, "💭 AI思考过程（完整）:")
        Log.d(TAG, modelResponse.thinking)
        Log.d(TAG, "🎯 AI操作指令（完整）:")
        Log.d(TAG, modelResponse.action)

        // 通过回调输出完整的思考过程到UI
        onStepCallback?.invoke(StepResult(
            success = false,
            finished = false,
            thinking = modelResponse.thinking,
            action = "分析中...",
            message = "正在分析屏幕，制定操作计划..."
        ))

        // 解析操作
        Log.d(TAG, "解析操作指令...")
        Log.d(TAG, "原始响应: ${modelResponse.action}")
        val actionJson = try {
            val parsed = responseActionParser.parseActionFromResponse(modelResponse.action)
            Log.d(TAG, "✅ 操作解析成功: $parsed")
            parsed
        } catch (e: Exception) {
            Log.e(TAG, "❌ 解析操作失败", e)
            Log.e(TAG, "原始操作文本: ${modelResponse.action}")
            e.printStackTrace()
            return@withContext StepResult(
                success = false,
                finished = false,
                thinking = modelResponse.thinking,
                action = modelResponse.action,
                message = "解析操作失败"
            )
        }

        // 执行操作（带重试机制）
        Log.d(TAG, "========================================")
        Log.d(TAG, "🎯 开始执行操作")
        Log.d(TAG, "操作指令: $actionJson")
        Log.d(TAG, "========================================")
        
        // 通过回调输出操作指令到UI
        onStepCallback?.invoke(StepResult(
            success = false,
            finished = false,
            thinking = modelResponse.thinking,
            action = actionJson,
            message = "正在执行操作..."
        ))
        
        val actionResult = executeActionWithRetry(
            actionJson = actionJson,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            currentApp = currentApp,
            task = currentTask ?: userPrompt
        )
        Log.d(TAG, "========================================")
        Log.d(TAG, "📊 操作执行结果")
        Log.d(TAG, "成功: ${actionResult.success}")
        Log.d(TAG, "完成: ${actionResult.shouldFinish}")
        if (actionResult.message != null) {
            Log.d(TAG, "消息: ${actionResult.message}")
        }
        Log.d(TAG, "========================================")
        
        // 更新失败计数
        failureTracker.recordActionResult(actionJson, actionResult)
        if (actionResult.message != null) {
            Log.d(TAG, "操作消息: ${actionResult.message}")
        }

        // 检查是否需要用户介入
        if (actionResult.requiresTakeover && actionResult.message != null) {
            Log.w(TAG, "========================================")
            Log.w(TAG, "⚠️ 需要用户介入")
            Log.w(TAG, "原因: ${actionResult.message}")
            Log.w(TAG, "========================================")
            stateMachine.markWaitingForUser()
            onUserInterventionCallback?.invoke(actionResult.message)
            
            sessionMemory.addInterventionMessage(actionResult.message)
            
            // 等待用户介入完成（给用户更多时间完成操作）
            Log.d(TAG, "⏳ 等待用户介入完成（5秒）...")
            onStepCallback?.invoke(StepResult(
                success = true,
                finished = false,
                thinking = "等待用户完成操作: ${actionResult.message}",
                action = "Take_over",
                message = "等待用户操作完成，将在5秒后继续..."
            ))
            val confirmed = AgentSessionCoordinator.waitForUserConfirmation(timeoutMs = 180_000)
            stateMachine.resumeAfterUserIntervention()
            Log.d(TAG, if (confirmed) "✅ 用户介入完成，继续执行任务" else "⏰ 用户介入等待超时，继续执行任务")
            
            // 用户介入后，重新截图并继续执行
            Log.d(TAG, "用户介入后，重新分析屏幕状态...")
            // 不返回，继续执行下一步
        }

        // 在操作执行后，移除最后一条用户消息（包含图片的那条）的图片
        sessionMemory.removeImageFromLastUserMessage()

        // 添加助手回复到上下文
        sessionMemory.addAssistantResponse(modelResponse.rawContent)
        
        // 如果操作失败，添加失败信息到上下文，帮助AI下次尝试不同方法
        if (!actionResult.success && actionResult.message != null) {
            sessionMemory.addFailureFeedback(actionResult.message)
            skillExecutionAdvisor.buildFailureRecoveryMessage(
                context = context,
                currentApp = currentApp,
                task = currentTask ?: userPrompt,
                actionJson = actionJson,
                actionResult = actionResult
            )?.let { recoveryMessage ->
                sessionMemory.add(Message("user", recoveryMessage))
            }
            Log.d(TAG, "已添加失败信息到上下文，帮助AI重新规划")
        }

        val stepResult = StepResult(
            success = actionResult.success,
            finished = actionResult.shouldFinish,
            thinking = modelResponse.thinking,
            action = actionJson,
            message = actionResult.message
        )

        Log.d(TAG, "步骤 $stepCount 完成: success=${stepResult.success}, finished=${stepResult.finished}")
        if (stepResult.finished) {
            Log.d(TAG, "✅ 任务完成: ${stepResult.message}")
        }
        Log.d(TAG, "========================================")

        onStepCallback?.invoke(stepResult)
        stepResult
    }

    /**
     * 带重试机制的操作执行
     * 如果操作失败，会根据失败类型尝试不同的策略
     */
    private suspend fun executeActionWithRetry(
        actionJson: String,
        screenWidth: Int,
        screenHeight: Int,
        currentApp: String?,
        task: String?
    ): ActionResult {
        // 如果连续失败次数过多，尝试让AI重新规划
        failureTracker.maybeReplanHintForExecution()?.let { lastFailedAction ->
            Log.w(TAG, "⚠️ 连续失败 ${failureTracker.consecutiveFailures} 次，可能需要重新规划策略")
            sessionMemory.addReplanHint(lastFailedAction)
        }

        return try {
            val primaryResult = actionHandler.execute(actionJson, screenWidth, screenHeight)
            if (primaryResult.success) {
                return primaryResult
            }

            val fallbackActions = skillActionInterceptor.fallbackActions(
                context = context,
                currentApp = currentApp,
                task = task,
                actionJson = actionJson,
                actionResult = primaryResult
            )

            for ((index, fallbackAction) in fallbackActions.withIndex()) {
                Log.w(TAG, "⚠️ 尝试 Skill Fallback #${index + 1}: $fallbackAction")
                val fallbackResult = actionHandler.execute(fallbackAction, screenWidth, screenHeight)
                if (fallbackResult.success) {
                    return fallbackResult.copy(
                        message = "Skill fallback 执行成功: ${fallbackResult.message ?: fallbackAction}"
                    )
                }
            }

            primaryResult
        } catch (e: Exception) {
            Log.e(TAG, "❌ 操作执行失败", e)
            e.printStackTrace()
            ActionResult(
                success = false,
                shouldFinish = false,
                message = "操作执行失败: ${e.message}"
            )
        }
    }

    /**
     * 截图（使用 ScreenshotManager）
     * 仅在视觉模式或混合模式下使用
     */
    private suspend fun captureScreenshot(): Bitmap? = withContext(Dispatchers.IO) {
        val manager = screenshotManager
        if (manager == null) {
            Log.w(TAG, "ScreenshotManager 未初始化，无法截图（当前模式: $mode）")
            return@withContext null
        }

        manager.captureScreen()
    }

}

/**
 * 步骤结果
 */
data class StepResult(
    val success: Boolean,
    val finished: Boolean,
    val thinking: String,
    val action: String,
    val message: String? = null
)
