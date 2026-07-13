package com.mobileagent.phoneagent.harness.act

import android.content.Context
import android.util.Log
import com.mobileagent.phoneagent.action.Action
import com.mobileagent.phoneagent.action.ActionParser
import com.mobileagent.phoneagent.action.ActionHandler
import com.mobileagent.phoneagent.action.FinishAction
import com.mobileagent.phoneagent.action.LaunchAction
import com.mobileagent.phoneagent.harness.recover.FailureType
import com.mobileagent.phoneagent.harness.trace.TaskNoteExtractor
import com.mobileagent.phoneagent.skill.SkillActionInterceptor

interface ActionExecutor {
    suspend fun execute(request: ExecutionRequest): ExecutionResult
}

class DefaultActionExecutor(
    private val context: Context,
    private val actionHandler: ActionHandler,
    private val skillActionInterceptor: SkillActionInterceptor,
    private val appLaunchController: AppLaunchController,
    private val actionParser: ActionParser = ActionParser(),
    private val executionHumanizer: ExecutionHumanizer = ExecutionHumanizer.fromSettings(context)
) : ActionExecutor {
    private val tag = "DefaultActionExecutor"

    override suspend fun execute(request: ExecutionRequest): ExecutionResult {
        return try {
            val humanized = executionHumanizer.humanize(request.actionJson)
            val action = runCatching { actionParser.parse(humanized.actionJson) }.getOrNull()
            if (action is LaunchAction) {
                return appLaunchController.launch(
                    AppLaunchRequest(
                        appName = action.appName,
                        actionJson = humanized.actionJson,
                        currentTask = request.taskGoal
                    )
                ).withHumanizationTrace(humanized.trace).withTaskNote()
            }

            val primaryResult = actionHandler.execute(
                humanized.actionJson,
                request.screenWidth,
                request.screenHeight
            )
            if (primaryResult.success) {
                return primaryResult
                    .toExecutionResult(humanized.actionJson, humanized.trace)
                    .withTerminalVerificationRequirement(action)
            }

            val fallbackActions = skillActionInterceptor.fallbackActions(
                context = context,
                currentApp = request.currentApp,
                task = request.taskGoal,
                actionJson = humanized.actionJson,
                actionResult = primaryResult
            )

            for ((index, fallbackAction) in fallbackActions.withIndex()) {
                Log.w(tag, "尝试 Skill Fallback #${index + 1}: $fallbackAction")
                val fallbackHumanized = executionHumanizer.humanize(fallbackAction)
                val fallbackResult = actionHandler.execute(
                    fallbackHumanized.actionJson,
                    request.screenWidth,
                    request.screenHeight
                )
                if (fallbackResult.success) {
                    val fallbackParsedAction = runCatching {
                        actionParser.parse(fallbackHumanized.actionJson)
                    }.getOrNull()
                    return fallbackResult.copy(
                        message = "Skill fallback 执行成功: ${fallbackResult.message ?: fallbackHumanized.actionJson}"
                    ).toExecutionResult(
                        fallbackHumanized.actionJson,
                        fallbackHumanized.trace ?: humanized.trace
                    ).withTerminalVerificationRequirement(fallbackParsedAction)
                }
            }

            primaryResult
                .toExecutionResult(humanized.actionJson, humanized.trace)
                .withTerminalVerificationRequirement(action)
        } catch (e: Exception) {
            Log.e(tag, "执行动作失败", e)
            ExecutionResult(
                success = false,
                shouldFinish = false,
                message = "操作执行失败: ${e.message}",
                actionJson = request.actionJson,
                failureType = FailureType.ACTION_EXECUTION_FAILED
            )
        }
    }

    private fun com.mobileagent.phoneagent.action.ActionResult.toExecutionResult(
        actionJson: String,
        humanizationTrace: ExecutionHumanizationTrace? = null
    ): ExecutionResult {
        return ExecutionResult(
            success = success,
            shouldFinish = shouldFinish,
            message = message,
            actionJson = actionJson,
            requiresTakeover = requiresTakeover,
            failureType = when {
                requiresTakeover -> FailureType.USER_TAKEOVER_REQUIRED
                !success && message?.contains("未找到应用") == true -> FailureType.APP_NOT_FOUND
                !success && message?.contains("无障碍服务未启用") == true -> FailureType.PERMISSION_MISSING
                !success -> FailureType.ACTION_EXECUTION_FAILED
                else -> null
            },
            humanizationTrace = humanizationTrace,
            userInteractionRequest = userInteractionRequest,
            clipboardTrace = clipboardTrace
        ).withTaskNote()
    }

    private fun ExecutionResult.withTaskNote(): ExecutionResult {
        if (!success || taskNote != null) {
            return this
        }
        val note = TaskNoteExtractor.fromActionJson(actionJson) ?: return this
        return copy(
            taskNote = note,
            message = "${message ?: "动作执行完成"} | ${note.toDisplayText()}"
        )
    }

    private fun ExecutionResult.withHumanizationTrace(
        humanizationTrace: ExecutionHumanizationTrace?
    ): ExecutionResult {
        return if (humanizationTrace == null) {
            this
        } else {
            copy(humanizationTrace = humanizationTrace)
        }
    }

    private fun ExecutionResult.withTerminalVerificationRequirement(action: Action?): ExecutionResult {
        return copy(
            terminalVerificationRequirement = if (action is FinishAction) {
                TerminalVerificationRequirement.FINAL_OBSERVATION
            } else {
                TerminalVerificationRequirement.NONE
            }
        )
    }
}
