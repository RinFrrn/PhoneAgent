package com.mobileagent.phoneagent.harness.recover

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
        if (execution.requiresTakeover) {
            return FailureType.USER_TAKEOVER_REQUIRED
        }

        val rawMessage = execution.message.orEmpty()
        val normalized = rawMessage.lowercase()
        return when {
            "未找到应用" in rawMessage -> FailureType.APP_NOT_FOUND
            "无障碍服务未启用" in rawMessage -> FailureType.PERMISSION_MISSING
            verification != null && !verification.passed -> FailureType.VERIFICATION_FAILED
            !execution.success && "失败" in normalized -> FailureType.ACTION_EXECUTION_FAILED
            execution.success && verification != null && !verification.passed -> FailureType.ACTION_NOT_EFFECTIVE
            else -> FailureType.UNKNOWN
        }
    }
}
