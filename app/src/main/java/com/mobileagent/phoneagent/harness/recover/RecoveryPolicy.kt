package com.mobileagent.phoneagent.harness.recover

import com.mobileagent.phoneagent.harness.act.ExecutionResult
import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.spec.TaskSpec

data class RecoveryDecision(
    val stopTask: Boolean = false,
    val userMessage: String? = null,
    val requiresUserTakeover: Boolean = false
)

class DefaultRecoveryPolicy {
    fun decide(
        failureType: FailureType,
        taskSpec: TaskSpec,
        observation: Observation,
        execution: ExecutionResult?
    ): RecoveryDecision {
        return when (failureType) {
            FailureType.MODEL_BALANCE -> RecoveryDecision(
                stopTask = true,
                userMessage = "模型账户余额不足，停止任务。请充值或更换模型后重试。"
            )
            FailureType.MODEL_AUTH -> RecoveryDecision(
                stopTask = true,
                userMessage = "模型鉴权失败，停止任务。请检查 API Key 或服务商配置。"
            )
            FailureType.PERMISSION_MISSING -> RecoveryDecision(
                stopTask = true,
                userMessage = "运行权限缺失，停止任务。请检查无障碍、录屏或悬浮窗权限。"
            )
            FailureType.APP_NOT_FOUND -> RecoveryDecision(
                userMessage = "目标应用未找到。建议检查应用名称、别名或是否已安装。"
            )
            FailureType.APP_LAUNCH_BLOCKED -> RecoveryDecision(
                userMessage = "系统阻止了后台应用跳转。请通过悬浮窗或通知点击打开目标应用。"
            )
            FailureType.APP_LAUNCH_CONFIRMATION_REQUIRED -> RecoveryDecision(
                requiresUserTakeover = true,
                userMessage = execution?.message ?: "应用跳转需要用户确认。请点击悬浮窗或系统弹窗后继续。"
            )
            FailureType.APP_LAUNCH_TARGET_NOT_REACHED -> RecoveryDecision(
                userMessage = "已发送应用跳转请求，但未到达目标应用。请结合当前页面重新判断，不要重复后台启动。"
            )
            FailureType.ACTION_NOT_EFFECTIVE -> RecoveryDecision(
                userMessage = buildString {
                    append("上一步动作已执行，但页面没有产生可观察变化，疑似点击/滑动无效。")
                    observation.currentApp?.let { append(" 当前应用: $it。") }
                    execution?.message?.let { append(" 细节: $it。") }
                    append("禁止继续重复同一动作、同一坐标或同一路径。")
                    append("请改用不同策略：换一个更明确的控件坐标、先滑动查找、返回后重新进入、等待页面加载、使用 Launch 回到目标应用，或使用 Take_over 请求用户接管。")
                }
            )
            FailureType.VERIFICATION_FAILED -> RecoveryDecision(
                userMessage = buildString {
                    append("上一步操作未产生预期效果。")
                    observation.currentApp?.let { append(" 当前应用: $it。") }
                    execution?.message?.let { append(" 失败信息: $it。") }
                    append("请结合当前页面重新规划，不要重复同一操作。")
                }
            )
            FailureType.RECORDED_TARGET_MISSING -> RecoveryDecision(
                userMessage = "复用已学习路径时未找到语义目标。请重新观察当前页面，优先等待加载、关闭遮挡弹窗、滚动查找目标；不要直接使用旧坐标。"
            )
            FailureType.RECORDED_STATE_TIMEOUT -> RecoveryDecision(
                userMessage = "复用已学习路径时等待页面状态超时。请检查是否仍在目标应用，必要时返回上一层或重新进入目标页面。"
            )
            FailureType.RECORDED_OBSTRUCTION_DETECTED -> RecoveryDecision(
                userMessage = "复用已学习路径时疑似遇到广告、弹窗或权限提示遮挡。请优先关闭遮挡物，确认页面恢复后再继续原路径。"
            )
            FailureType.USER_TAKEOVER_REQUIRED -> RecoveryDecision(
                requiresUserTakeover = true,
                userMessage = execution?.message ?: "需要用户接管"
            )
            FailureType.MAX_STEPS_EXCEEDED -> RecoveryDecision(
                stopTask = true,
                userMessage = "任务达到最大步数仍未完成: ${taskSpec.goal}"
            )
            else -> RecoveryDecision()
        }
    }
}
