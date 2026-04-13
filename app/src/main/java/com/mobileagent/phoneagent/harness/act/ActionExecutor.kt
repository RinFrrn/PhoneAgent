package com.mobileagent.phoneagent.harness.act

import android.content.Context
import android.util.Log
import com.mobileagent.phoneagent.action.ActionHandler
import com.mobileagent.phoneagent.harness.recover.FailureType
import com.mobileagent.phoneagent.skill.SkillActionInterceptor

interface ActionExecutor {
    suspend fun execute(request: ExecutionRequest): ExecutionResult
}

class DefaultActionExecutor(
    private val context: Context,
    private val actionHandler: ActionHandler,
    private val skillActionInterceptor: SkillActionInterceptor
) : ActionExecutor {
    private val tag = "DefaultActionExecutor"

    override suspend fun execute(request: ExecutionRequest): ExecutionResult {
        return try {
            val primaryResult = actionHandler.execute(
                request.actionJson,
                request.screenWidth,
                request.screenHeight
            )
            if (primaryResult.success) {
                return primaryResult.toExecutionResult(request.actionJson)
            }

            val fallbackActions = skillActionInterceptor.fallbackActions(
                context = context,
                currentApp = request.currentApp,
                task = request.taskGoal,
                actionJson = request.actionJson,
                actionResult = primaryResult
            )

            for ((index, fallbackAction) in fallbackActions.withIndex()) {
                Log.w(tag, "尝试 Skill Fallback #${index + 1}: $fallbackAction")
                val fallbackResult = actionHandler.execute(
                    fallbackAction,
                    request.screenWidth,
                    request.screenHeight
                )
                if (fallbackResult.success) {
                    return fallbackResult.copy(
                        message = "Skill fallback 执行成功: ${fallbackResult.message ?: fallbackAction}"
                    ).toExecutionResult(fallbackAction)
                }
            }

            primaryResult.toExecutionResult(request.actionJson)
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

    private fun com.mobileagent.phoneagent.action.ActionResult.toExecutionResult(actionJson: String): ExecutionResult {
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
            }
        )
    }
}
