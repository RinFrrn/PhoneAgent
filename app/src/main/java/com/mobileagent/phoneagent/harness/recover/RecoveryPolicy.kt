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
            FailureType.VERIFICATION_FAILED, FailureType.ACTION_NOT_EFFECTIVE -> RecoveryDecision(
                userMessage = buildString {
                    append("上一步操作未产生预期效果。")
                    observation.currentApp?.let { append(" 当前应用: $it。") }
                    execution?.message?.let { append(" 失败信息: $it。") }
                    append("请结合当前页面重新规划，不要重复同一操作。")
                }
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
