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
import com.mobileagent.phoneagent.harness.act.DefaultActionExecutor
import com.mobileagent.phoneagent.harness.observe.DefaultObservationCollector
import com.mobileagent.phoneagent.harness.plan.LlmPlanner
import com.mobileagent.phoneagent.harness.runtime.HarnessRuntime
import com.mobileagent.phoneagent.harness.runtime.HarnessStepRecord
import com.mobileagent.phoneagent.harness.runtime.StepStatus
import com.mobileagent.phoneagent.harness.spec.TaskSpec
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
    private val observationCollector = DefaultObservationCollector(screenObserver)
    private val planner = LlmPlanner(context, modelClient, responseActionParser, skillPromptAugmentor)
    private val actionExecutor = DefaultActionExecutor(context, actionHandler, skillActionInterceptor)
    private val harnessRuntime = HarnessRuntime(
        context = context,
        observationCollector = observationCollector,
        planner = planner,
        actionExecutor = actionExecutor,
        sessionMemory = sessionMemory,
        stateMachine = stateMachine,
        failureTracker = failureTracker,
        skillExecutionAdvisor = skillExecutionAdvisor
    )
    
    private var screenshotManager: ScreenshotManager? = null
    private var currentTask: String? = null // 保存当前任务描述

    /**
     * 运行任务
     */
    fun run(task: String, onComplete: (TaskOutcome) -> Unit) {
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
                            onComplete(TaskOutcome(false, "MediaProjection 已过期，请重新授权屏幕录制权限后重试"))
                            return@launch
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ 初始化 ScreenshotManager 失败", e)
                            stateMachine.markFailed()
                            onComplete(TaskOutcome(false, "初始化截图管理器失败: ${e.message}"))
                            return@launch
                        }
                    } else {
                        Log.e(TAG, "❌ MediaProjection 为 null，无法初始化 ScreenshotManager（模式: $mode）")
                        stateMachine.markFailed()
                        onComplete(TaskOutcome(false, "MediaProjection 未初始化，请先授权屏幕录制权限"))
                        return@launch
                    }
                } else {
                    Log.d(TAG, "✅ 无障碍模式，无需初始化 ScreenshotManager")
                }
                
                // 初始化系统提示词
                sessionMemory.addSystemMessage(systemPrompt)
                Log.d(TAG, "系统提示词已添加")

                harnessRuntime.run(
                    taskSpec = TaskSpec(
                        id = "task-${System.currentTimeMillis()}",
                        goal = task,
                        mode = mode.name,
                        maxSteps = maxSteps
                    ),
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    onStepRecord = { record ->
                        logStepRecord(record)
                        onStepCallback?.invoke(record.toStepResult())
                    },
                    onUserIntervention = onUserInterventionCallback,
                    onComplete = onComplete
                )
            } catch (e: CancellationException) {
                Log.d(TAG, "任务已取消")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 执行任务失败", e)
                e.printStackTrace()
                stateMachine.markFailed()
                onComplete(TaskOutcome(false, "任务执行失败: ${e.message}"))
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

    private fun logStepRecord(record: HarnessStepRecord) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "📊 Harness 步骤结果")
        Log.d(TAG, "步骤: ${record.stepIndex}")
        Log.d(TAG, "状态: ${record.status}")
        Log.d(TAG, "应用: ${record.observation.currentApp}")
        Log.d(TAG, "连续失败次数: ${failureTracker.consecutiveFailures}")
        record.decision?.let {
            Log.d(TAG, "思考: ${it.thinking.take(100)}...")
            Log.d(TAG, "操作: ${it.actionJson.take(100)}...")
        }
        record.execution?.message?.let { Log.d(TAG, "消息: $it") }
        record.errorMessage?.let { Log.d(TAG, "错误: $it") }
        Log.d(TAG, "========================================")
    }

    private fun HarnessStepRecord.toStepResult(): StepResult {
        return StepResult(
            success = execution?.success ?: false,
            finished = status == StepStatus.FINISHED,
            thinking = decision?.thinking ?: (errorMessage ?: "步骤执行失败"),
            action = decision?.actionJson ?: "",
            message = execution?.message ?: errorMessage
        )
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

data class TaskOutcome(
    val success: Boolean,
    val message: String
)
