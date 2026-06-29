package com.mobileagent.phoneagent.harness.runtime

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.mobileagent.phoneagent.SettingsActivity
import com.mobileagent.phoneagent.agent.AgentSessionCoordinator
import com.mobileagent.phoneagent.agent.Mode
import com.mobileagent.phoneagent.agent.PhoneAgent
import com.mobileagent.phoneagent.model.ModelClient
import com.mobileagent.phoneagent.service.AgentForegroundService
import com.mobileagent.phoneagent.service.FloatingOverlayService
import com.mobileagent.phoneagent.service.PhoneAgentAccessibilityService

enum class TaskRunSource {
    MAIN,
    QUICK_ASK
}

data class TaskRunRequest(
    val task: String,
    val mode: Mode,
    val source: TaskRunSource,
    val mediaProjection: MediaProjection? = null
)

object TaskRunController {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var phoneAgent: PhoneAgent? = null

    @Volatile
    private var activeMediaProjection: MediaProjection? = null

    fun setMediaProjection(mediaProjection: MediaProjection?) {
        activeMediaProjection = mediaProjection
    }

    fun hasMediaProjection(): Boolean = activeMediaProjection != null

    fun isRunning(): Boolean = phoneAgent?.isTaskRunning() == true

    fun start(context: Context, request: TaskRunRequest): Boolean {
        val appContext = context.applicationContext
        val task = request.task.trim()
        if (task.isBlank()) {
            FloatingOverlayService.update(
                appContext,
                status = "请输入任务",
                detail = "描述你想让 Phone Agent 在当前页面完成什么。",
                task = "一键问屏"
            )
            return false
        }

        if (isRunning()) {
            FloatingOverlayService.update(
                appContext,
                status = "任务执行中",
                detail = "请先停止当前任务，再发起新的问屏任务。",
                task = task
            )
            return false
        }

        val accessibilityService = PhoneAgentAccessibilityService.getInstance()
        if (accessibilityService == null) {
            FloatingOverlayService.update(
                appContext,
                status = "无障碍未连接",
                detail = "请先在主页开启无障碍服务，或等待系统服务连接后重试。",
                task = task
            )
            return false
        }

        val mediaProjection = request.mediaProjection ?: activeMediaProjection
        if ((request.mode == Mode.VISION || request.mode == Mode.HYBRID) && mediaProjection == null) {
            FloatingOverlayService.update(
                appContext,
                status = "需要录屏授权",
                detail = "当前模式需要先回到主页完成屏幕录制授权。",
                task = task
            )
            return false
        }

        val modelConfig = SettingsActivity.getActiveModelConfig(appContext)
        if (!modelConfig.isConfigured) {
            FloatingOverlayService.update(
                appContext,
                status = "模型未配置",
                detail = "请先在主页模型设置中补全模型地址、名称和必要的 API Key。",
                task = task
            )
            return false
        }

        val gestureBounds = accessibilityService.getGestureDisplayBounds()
        val modelClient = ModelClient(
            baseUrl = modelConfig.baseUrl,
            modelName = modelConfig.modelName,
            apiKey = modelConfig.apiKey,
            provider = modelConfig.provider,
            temperature = modelConfig.temperature,
            topP = modelConfig.topP
        )
        val agent = PhoneAgent(
            context = appContext,
            modelClient = modelClient,
            modelDisplayName = modelConfig.displayName,
            accessibilityService = accessibilityService,
            mediaProjection = if (request.mode == Mode.ACCESSIBILITY) null else mediaProjection,
            screenWidth = gestureBounds.width,
            screenHeight = gestureBounds.height,
            maxSteps = Int.MAX_VALUE,
            systemPrompt = SystemPromptBuilder.build(appContext, request.mode),
            mode = request.mode,
            onStepCallback = { stepResult ->
                val status = when {
                    stepResult.finished -> "任务完成"
                    stepResult.success -> "步骤成功"
                    else -> "步骤失败"
                }
                val detail = stepResult.message ?: stepResult.thinking.take(120)
                FloatingOverlayService.update(
                    appContext,
                    status = status,
                    detail = detail.ifBlank { "等待下一步执行结果" },
                    task = task
                )
                updateNotification(appContext, "$status: $detail")
            },
            onRuntimeStatusCallback = { statusUpdate ->
                val isGenerating = statusUpdate.phase == RuntimePhase.MODEL_GENERATING
                FloatingOverlayService.update(
                    appContext,
                    status = statusUpdate.status,
                    detail = statusUpdate.detail,
                    task = task,
                    activityStatus = if (isGenerating) "AI 生成中" else null,
                    activityAnimating = isGenerating
                )
                updateNotification(appContext, "${statusUpdate.status}: ${statusUpdate.detail}")
            },
            onUserInterventionCallback = { message ->
                FloatingOverlayService.update(
                    appContext,
                    status = "等待用户处理",
                    detail = message,
                    task = task,
                    interactionRequired = true
                )
                showUserIntervention(appContext, message)
            }
        )

        phoneAgent = agent
        startTaskForegroundService(appContext, task, modelConfig.baseUrl, modelConfig.modelName, request.mode)
        AgentSessionCoordinator.register(task) {
            stop(appContext)
        }
        FloatingOverlayService.update(
            appContext,
            status = "任务启动中",
            detail = "正在读取当前页面状态。",
            task = task
        )

        agent.run(task) { result ->
            mainHandler.post {
                val status = if (result.success) "任务完成" else "任务失败"
                FloatingOverlayService.update(
                    appContext,
                    status = status,
                    detail = result.message,
                    task = task
                )
                phoneAgent = null
                AgentSessionCoordinator.clear()
                stopTaskForegroundService(appContext)
            }
        }
        return true
    }

    fun stop(context: Context) {
        val appContext = context.applicationContext
        phoneAgent?.stop()
        phoneAgent = null
        AgentSessionCoordinator.clear()
        FloatingOverlayService.update(
            appContext,
            status = "任务已停止",
            detail = "可以重新输入问屏任务。",
            task = "一键问屏"
        )
        stopTaskForegroundService(appContext)
    }

    private fun startTaskForegroundService(
        context: Context,
        task: String,
        baseUrl: String,
        modelName: String,
        mode: Mode
    ) {
        val intent = Intent(context, AgentForegroundService::class.java).apply {
            action = AgentForegroundService.ACTION_START_TASK
            putExtra(AgentForegroundService.EXTRA_TASK, task)
            putExtra(AgentForegroundService.EXTRA_BASE_URL, baseUrl)
            putExtra(AgentForegroundService.EXTRA_MODEL_NAME, modelName)
            putExtra(AgentForegroundService.EXTRA_MODE, mode.name)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopTaskForegroundService(context: Context) {
        val intent = Intent(context, AgentForegroundService::class.java).apply {
            action = AgentForegroundService.ACTION_STOP_TASK
        }
        context.stopService(intent)
    }

    private fun updateNotification(context: Context, content: String) {
        val intent = Intent(context, AgentForegroundService::class.java).apply {
            action = "UPDATE_NOTIFICATION"
            putExtra("content", content)
        }
        context.startService(intent)
    }

    private fun showUserIntervention(context: Context, message: String) {
        val intent = Intent(context, AgentForegroundService::class.java).apply {
            action = "SHOW_USER_INTERVENTION"
            putExtra("message", message)
        }
        context.startService(intent)
    }
}
