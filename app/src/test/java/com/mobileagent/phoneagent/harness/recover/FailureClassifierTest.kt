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

    @Test
    fun classifySuccessfulExecutionWithFailedVerificationAsActionNotEffective() {
        val execution = ExecutionResult(
            success = true,
            shouldFinish = false,
            message = "点击成功",
            actionJson = """{"_metadata":"do","action":"Tap","element":[500,500]}"""
        )

        val failureType = classifier.classifyExecutionFailure(
            execution = execution,
            verification = VerificationResult(false, 0.85f, "点击手势已调度，但页面内容和当前应用均未变化")
        )

        assertEquals(FailureType.ACTION_NOT_EFFECTIVE, failureType)
    }

    @Test
    fun classifySensitiveVerificationAsUserConfirmationFailure() {
        val execution = ExecutionResult(
            success = true,
            shouldFinish = false,
            message = "点击成功",
            actionJson = """{"_metadata":"do","action":"Tap","element":[500,900]}"""
        )

        val failureType = classifier.classifyExecutionFailure(
            execution = execution,
            verification = VerificationResult(false, 0.96f, "检测到敏感确认页面，需要用户确认或接管: 支付密码")
        )

        assertEquals(FailureType.SENSITIVE_CONFIRMATION_REQUIRED, failureType)
    }

    @Test
    fun classifyEnglishAuthAndPermissionFailures() {
        assertEquals(
            FailureType.MODEL_AUTH,
            classifier.classifyModelFailure("Request failed: 403 forbidden")
        )
        assertEquals(
            FailureType.PERMISSION_MISSING,
            classifier.classifyObservationFailure("SecurityException: permission denied")
        )
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
