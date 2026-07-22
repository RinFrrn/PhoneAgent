package com.mobileagent.phoneagent.harness.recover

import com.mobileagent.phoneagent.action.UserInteractionKind
import com.mobileagent.phoneagent.action.UserInteractionRequest
import com.mobileagent.phoneagent.harness.act.ExecutionResult
import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.spec.TaskSpec

enum class RecoveryRoute {
    NONE,
    RETRY,
    REPLAN,
    USER_INTERVENTION,
    STOP
}

data class RecoveryContext(
    val attempt: Int = 1
)

data class RecoveryTrace(
    val route: RecoveryRoute,
    val failureType: FailureType,
    val attempt: Int,
    val maxAttempts: Int? = null,
    val delayMs: Long = 0L,
    val reason: String
)

data class RecoveryDecision(
    val route: RecoveryRoute = RecoveryRoute.NONE,
    val userMessage: String? = null,
    val requiresUserTakeover: Boolean = false,
    val userInteractionRequest: UserInteractionRequest? = null,
    val delayMs: Long = 0L,
    val maxAttempts: Int? = null,
    val reason: String = "无需恢复"
) {
    val stopTask: Boolean
        get() = route == RecoveryRoute.STOP

    val shouldRetry: Boolean
        get() = route == RecoveryRoute.RETRY

    fun toTrace(failureType: FailureType, context: RecoveryContext): RecoveryTrace {
        return RecoveryTrace(
            route = route,
            failureType = failureType,
            attempt = context.attempt,
            maxAttempts = maxAttempts,
            delayMs = delayMs,
            reason = reason
        )
    }
}

class DefaultRecoveryPolicy {
    fun decide(
        failureType: FailureType,
        taskSpec: TaskSpec,
        observation: Observation,
        execution: ExecutionResult?,
        context: RecoveryContext = RecoveryContext()
    ): RecoveryDecision {
        return when (failureType) {
            FailureType.OBSERVATION_FAILED -> retryOrStop(
                context = context,
                maxRetries = TRANSIENT_OBSERVATION_RETRIES,
                delayMs = OBSERVATION_RETRY_DELAY_MS,
                retryMessage = "页面观察暂时失败，正在重新采集当前页面。",
                stopMessage = "连续无法读取当前页面，任务已停止。请检查页面是否稳定、录屏会话是否有效，或切换到无障碍/混合模式后重试。",
                reason = "瞬时页面采集失败，按参考运行时策略进行有界重试"
            )
            FailureType.MODEL_REQUEST_FAILED -> retryOrStop(
                context = context,
                maxRetries = TRANSIENT_MODEL_RETRIES,
                delayMs = MODEL_RETRY_DELAY_MS,
                retryMessage = "模型请求暂时失败，正在重新观察页面后重试。",
                stopMessage = "模型请求连续失败，任务已停止。请检查网络、服务地址、限流状态或模型可用性。",
                reason = "瞬时模型请求失败，重新观察后进行有界重试"
            )
            FailureType.MODEL_BALANCE -> stop(
                "模型账户余额不足，停止任务。请充值或更换模型后重试。",
                "账户问题不可通过运行时重试恢复"
            )
            FailureType.MODEL_AUTH -> stop(
                "模型鉴权失败，停止任务。请检查 API Key 或服务商配置。",
                "鉴权问题不可通过运行时重试恢复"
            )
            FailureType.PERMISSION_MISSING -> stop(
                "运行权限缺失，停止任务。请检查无障碍、录屏或悬浮窗权限。",
                "缺失系统权限，需要用户在系统设置中处理"
            )
            FailureType.APP_NOT_FOUND -> replan(
                "目标应用未找到。请检查应用名称、别名或是否已安装，并结合当前页面选择其他可行入口。",
                "应用解析失败，交给规划层更换入口"
            )
            FailureType.APP_LAUNCH_BLOCKED -> userIntervention(
                message = "系统阻止了后台应用跳转。请通过悬浮窗或通知点击打开目标应用，完成后继续。",
                reason = "Android 后台启动限制需要用户前台手势"
            )
            FailureType.APP_LAUNCH_CONFIRMATION_REQUIRED -> userIntervention(
                message = execution?.message ?: "应用跳转需要用户确认。请点击悬浮窗或系统弹窗后继续。",
                reason = "应用启动链路要求用户确认"
            )
            FailureType.APP_LAUNCH_TARGET_NOT_REACHED -> replan(
                "已发送应用跳转请求，但未到达目标应用。请结合当前页面重新判断，不要重复后台启动。",
                "目标包名验证失败，需要重新规划导航路径"
            )
            FailureType.SENSITIVE_CONFIRMATION_REQUIRED -> RecoveryDecision(
                route = RecoveryRoute.USER_INTERVENTION,
                requiresUserTakeover = true,
                userMessage = "检测到敏感确认、登录、验证码、支付或订单页面。请用户确认页面内容并手动处理敏感步骤；完成后输入“确认继续”，或输入“取消任务”。",
                userInteractionRequest = UserInteractionRequest(
                    question = "敏感步骤处理完成后，是否确认继续自动化？",
                    options = listOf("确认继续", "取消任务"),
                    reason = "运行中检测到敏感页面，需要用户明确授权",
                    kind = UserInteractionKind.SENSITIVE_CONFIRMATION
                ),
                reason = "敏感检查点必须由用户明确授权"
            )
            FailureType.ACTION_NOT_EFFECTIVE -> if (execution?.requiresTakeover == true) {
                userIntervention(
                    message = execution.message ?: "连续操作无效，需要用户接管当前页面。",
                    reason = "连续页面停滞已达到用户介入阈值"
                )
            } else {
                replan(
                    message = buildString {
                        append("上一步动作已执行，但页面没有产生可观察变化，疑似点击/滑动无效。")
                        observation.currentApp?.let { append(" 当前应用: $it。") }
                        execution?.message?.let { append(" 细节: $it。") }
                        append("禁止继续重复同一动作、同一坐标或同一路径。")
                        append("请改用不同策略：换一个更明确的控件坐标、先滑动查找、返回后重新进入、等待页面加载、使用 Launch 回到目标应用，或使用 Take_over 请求用户接管。")
                    },
                    reason = "动作未改变页面状态，要求规划层切换策略"
                )
            }
            FailureType.ACTION_EXECUTION_FAILED -> replan(
                execution?.message ?: "动作执行失败。请结合当前页面改用不同的操作方式。",
                "动作执行层失败，保留当前观察并重新规划"
            )
            FailureType.VERIFICATION_FAILED -> replan(
                message = buildString {
                    append("上一步操作未产生预期效果。")
                    observation.currentApp?.let { append(" 当前应用: $it。") }
                    execution?.message?.let { append(" 失败信息: $it。") }
                    append("请结合当前页面重新规划，不要重复同一操作。")
                },
                reason = "动作后验证未通过，要求规划层根据新观察纠偏"
            )
            FailureType.RECORDED_TARGET_MISSING -> replan(
                "复用已学习路径时未找到语义目标。请重新观察当前页面，优先等待加载、关闭遮挡弹窗、滚动查找目标；不要直接使用旧坐标。",
                "已学习路径的语义目标缺失"
            )
            FailureType.RECORDED_STATE_TIMEOUT -> replan(
                "复用已学习路径时等待页面状态超时。请检查是否仍在目标应用，必要时返回上一层或重新进入目标页面。",
                "已学习路径的页面状态等待超时"
            )
            FailureType.RECORDED_OBSTRUCTION_DETECTED -> replan(
                "复用已学习路径时疑似遇到广告、弹窗或权限提示遮挡。请优先关闭遮挡物，确认页面恢复后再继续原路径。",
                "已学习路径遇到页面遮挡"
            )
            FailureType.USER_TAKEOVER_REQUIRED -> userIntervention(
                execution?.message ?: "需要用户接管",
                "执行层明确要求用户接管"
            )
            FailureType.USER_DENIED -> stop(
                execution?.message ?: "用户未授权继续执行，任务已取消。",
                "用户拒绝继续"
            )
            FailureType.USER_INTERVENTION_TIMEOUT -> stop(
                execution?.message ?: "等待用户明确确认超时，任务已停止。",
                "等待用户输入超过时限"
            )
            FailureType.TASK_STOPPED -> stop("任务已停止。", "用户或系统停止任务")
            FailureType.RUNTIME_INTERRUPTED -> stop("运行时已中断。", "运行进程中断，需要新会话安全续跑")
            FailureType.MAX_STEPS_EXCEEDED -> stop(
                "任务达到最大步数仍未完成: ${taskSpec.goal}",
                "已达到任务步数上限"
            )
            FailureType.UNKNOWN -> replan(
                execution?.message ?: "遇到未分类失败，请基于当前页面重新规划；若仍无法继续，请请求用户接管。",
                "未知执行失败，先采取保守重规划"
            )
        }
    }

    private fun retryOrStop(
        context: RecoveryContext,
        maxRetries: Int,
        delayMs: Long,
        retryMessage: String,
        stopMessage: String,
        reason: String
    ): RecoveryDecision {
        val maxAttempts = maxRetries + 1
        return if (context.attempt < maxAttempts) {
            RecoveryDecision(
                route = RecoveryRoute.RETRY,
                userMessage = retryMessage,
                delayMs = delayMs,
                maxAttempts = maxAttempts,
                reason = reason
            )
        } else {
            stop(
                message = stopMessage,
                reason = "$reason；已用尽 $maxRetries 次恢复重试",
                maxAttempts = maxAttempts
            )
        }
    }

    private fun replan(message: String, reason: String): RecoveryDecision {
        return RecoveryDecision(
            route = RecoveryRoute.REPLAN,
            userMessage = message,
            reason = reason
        )
    }

    private fun userIntervention(message: String, reason: String): RecoveryDecision {
        return RecoveryDecision(
            route = RecoveryRoute.USER_INTERVENTION,
            userMessage = message,
            requiresUserTakeover = true,
            userInteractionRequest = UserInteractionRequest(
                question = "请完成必要的手动处理；完成后输入“继续”。",
                options = listOf("继续"),
                reason = reason,
                kind = UserInteractionKind.QUESTION
            ),
            reason = reason
        )
    }

    private fun stop(
        message: String,
        reason: String,
        maxAttempts: Int? = null
    ): RecoveryDecision {
        return RecoveryDecision(
            route = RecoveryRoute.STOP,
            userMessage = message,
            maxAttempts = maxAttempts,
            reason = reason
        )
    }

    private companion object {
        const val TRANSIENT_OBSERVATION_RETRIES = 2
        const val TRANSIENT_MODEL_RETRIES = 2
        const val OBSERVATION_RETRY_DELAY_MS = 700L
        const val MODEL_RETRY_DELAY_MS = 1_200L
    }
}
