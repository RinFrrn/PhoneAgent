package com.mobileagent.phoneagent.harness.verify

import com.mobileagent.phoneagent.harness.act.AppLaunchStatus
import com.mobileagent.phoneagent.harness.act.AppLaunchStrategy
import com.mobileagent.phoneagent.harness.act.AppLaunchTrace
import com.mobileagent.phoneagent.harness.act.ExecutionResult
import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.spec.TaskSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericStepVerifierTest {
    private val verifier = GenericStepVerifier()
    private val taskSpec = TaskSpec(id = "test", goal = "打开微信", mode = "ACCESSIBILITY")
    private val launchAction = """{"_metadata":"do","action":"Launch","app":"微信"}"""

    @Test
    fun launchPassesWhenObservedPackageMatchesTarget() {
        val result = verifier.verify(
            before = Observation(currentApp = "Phone Agent", currentPackage = "com.mobileagent.phoneagent", contentItems = emptyList()),
            execution = launchExecution(targetPackage = "com.tencent.mm"),
            after = Observation(currentApp = "微信", currentPackage = "com.tencent.mm", contentItems = emptyList()),
            taskSpec = taskSpec
        )

        assertTrue(result.passed)
        assertTrue(result.reason.contains("目标包名"))
    }

    @Test
    fun launchFailsWhenObservedPackageDoesNotMatchTarget() {
        val result = verifier.verify(
            before = Observation(currentApp = "Phone Agent", currentPackage = "com.mobileagent.phoneagent", contentItems = emptyList()),
            execution = launchExecution(targetPackage = "com.tencent.mm"),
            after = Observation(currentApp = "微信", currentPackage = "com.example.fake", contentItems = emptyList()),
            taskSpec = taskSpec
        )

        assertFalse(result.passed)
        assertTrue(result.reason.contains("expected=com.tencent.mm"))
    }

    private fun launchExecution(targetPackage: String): ExecutionResult {
        return ExecutionResult(
            success = true,
            shouldFinish = false,
            message = "已发送启动请求",
            actionJson = launchAction,
            launchTrace = AppLaunchTrace(
                targetAppName = "微信",
                targetPackage = targetPackage,
                actualAppName = "微信",
                strategy = AppLaunchStrategy.DIRECT_VISIBLE,
                status = AppLaunchStatus.STARTED
            )
        )
    }
}
