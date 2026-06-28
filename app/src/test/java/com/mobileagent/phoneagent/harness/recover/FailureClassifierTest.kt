package com.mobileagent.phoneagent.harness.recover

import com.mobileagent.phoneagent.harness.act.AppLaunchStatus
import com.mobileagent.phoneagent.harness.act.AppLaunchTrace
import com.mobileagent.phoneagent.harness.act.ExecutionResult
import com.mobileagent.phoneagent.harness.verify.VerificationResult
import org.junit.Assert.assertEquals
import org.junit.Test

class FailureClassifierTest {
    private val classifier = FailureClassifier()

    @Test
    fun classifyLaunchConfirmationRequired() {
        val execution = launchExecution(
            status = AppLaunchStatus.CONFIRMATION_REQUIRED,
            afterPackage = "com.android.systemui"
        )

        val failureType = classifier.classifyExecutionFailure(
            execution = execution,
            verification = VerificationResult(false, 0.9f, "需要确认")
        )

        assertEquals(FailureType.APP_LAUNCH_CONFIRMATION_REQUIRED, failureType)
    }

    @Test
    fun classifyLaunchTargetNotReached() {
        val execution = launchExecution(
            status = AppLaunchStatus.STARTED,
            afterPackage = "com.example.other"
        )

        val failureType = classifier.classifyExecutionFailure(
            execution = execution,
            verification = VerificationResult(false, 0.9f, "未到达目标包名")
        )

        assertEquals(FailureType.APP_LAUNCH_TARGET_NOT_REACHED, failureType)
    }

    private fun launchExecution(status: AppLaunchStatus, afterPackage: String): ExecutionResult {
        return ExecutionResult(
            success = false,
            shouldFinish = false,
            message = "启动未完成",
            actionJson = """{"_metadata":"do","action":"Launch","app":"微信"}""",
            launchTrace = AppLaunchTrace(
                targetAppName = "微信",
                targetPackage = "com.tencent.mm",
                status = status,
                afterPackage = afterPackage
            )
        )
    }
}
