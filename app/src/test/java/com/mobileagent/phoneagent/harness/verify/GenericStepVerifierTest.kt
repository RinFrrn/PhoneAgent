package com.mobileagent.phoneagent.harness.verify

import com.mobileagent.phoneagent.harness.act.AppLaunchStatus
import com.mobileagent.phoneagent.harness.act.AppLaunchStrategy
import com.mobileagent.phoneagent.harness.act.AppLaunchTrace
import com.mobileagent.phoneagent.harness.act.ExecutionResult
import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.spec.TaskSpec
import com.mobileagent.phoneagent.model.ContentItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericStepVerifierTest {
    private val verifier = GenericStepVerifier()
    private val taskSpec = TaskSpec(id = "test", goal = "打开微信", mode = "ACCESSIBILITY")
    private val launchAction = """{"_metadata":"do","action":"Launch","app":"微信"}"""
    private val tapAction = """{"_metadata":"do","action":"Tap","element":[500,500]}"""

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

    @Test
    fun tapFailsWhenPageDoesNotChangeAfterGestureDispatch() {
        val unchanged = Observation(
            currentApp = "微信",
            currentPackage = "com.tencent.mm",
            contentItems = listOf(ContentItem(type = "text", text = "通讯录 发现 搜索 服务"))
        )

        val result = verifier.verify(
            before = unchanged,
            execution = ExecutionResult(
                success = true,
                shouldFinish = false,
                message = "点击成功",
                actionJson = tapAction
            ),
            after = unchanged.copy(timestamp = unchanged.timestamp + 1),
            taskSpec = taskSpec
        )

        assertFalse(result.passed)
        assertTrue(result.reason.contains("页面内容和当前应用均未变化"))
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
