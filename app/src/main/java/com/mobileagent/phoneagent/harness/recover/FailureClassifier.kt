package com.mobileagent.phoneagent.harness.recover

import com.mobileagent.phoneagent.harness.act.AppLaunchStatus
import com.mobileagent.phoneagent.harness.act.ExecutionResult
import com.mobileagent.phoneagent.harness.verify.VerificationResult

class FailureClassifier {
    fun classifyModelFailure(message: String): FailureType {
        val normalized = message.lowercase()
        return when {
            "402" in normalized || "insufficient_balance" in normalized || "balance" in normalized -> FailureType.MODEL_BALANCE
            "401" in normalized || "unauthorized" in normalized || "invalid api key" in normalized -> FailureType.MODEL_AUTH
            else -> FailureType.MODEL_REQUEST_FAILED
        }
    }

    fun classifyObservationFailure(message: String): FailureType {
        val normalized = message.lowercase()
        return when {
            "权限" in message || "mediaprojection" in normalized -> FailureType.PERMISSION_MISSING
            else -> FailureType.OBSERVATION_FAILED
        }
    }

    fun classifyExecutionFailure(
        execution: ExecutionResult,
        verification: VerificationResult?
    ): FailureType {
        val rawMessage = execution.message.orEmpty()
        val normalized = rawMessage.lowercase()
        execution.launchTrace?.let { trace ->
            if (trace.status == AppLaunchStatus.CONFIRMATION_REQUIRED) {
                return FailureType.APP_LAUNCH_CONFIRMATION_REQUIRED
            }
            if (trace.targetPackage != null && trace.afterPackage != null && trace.afterPackage != trace.targetPackage) {
                return FailureType.APP_LAUNCH_TARGET_NOT_REACHED
            }
        }
        if (execution.requiresTakeover) {
            return FailureType.USER_TAKEOVER_REQUIRED
        }
        if (verification?.reason?.contains("敏感", ignoreCase = true) == true) {
            return FailureType.SENSITIVE_CONFIRMATION_REQUIRED
        }

        return when {
            "未找到应用" in rawMessage -> FailureType.APP_NOT_FOUND
            "系统阻止启动应用" in rawMessage -> FailureType.APP_LAUNCH_BLOCKED
            "应用启动需要确认" in rawMessage || "需要点击悬浮窗或通知" in rawMessage -> FailureType.APP_LAUNCH_CONFIRMATION_REQUIRED
            "未到达目标包名" in rawMessage -> FailureType.APP_LAUNCH_TARGET_NOT_REACHED
            "无障碍服务未启用" in rawMessage -> FailureType.PERMISSION_MISSING
            execution.success && verification != null && !verification.passed -> FailureType.ACTION_NOT_EFFECTIVE
            verification != null && !verification.passed -> FailureType.VERIFICATION_FAILED
            !execution.success && "失败" in normalized -> FailureType.ACTION_EXECUTION_FAILED
            else -> FailureType.UNKNOWN
        }
    }
}
