package com.mobileagent.phoneagent.harness.verify

import com.mobileagent.phoneagent.action.UserInteractionRequest
import com.mobileagent.phoneagent.action.ClipboardOperation
import com.mobileagent.phoneagent.action.ClipboardTrace
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
    private val swipeAction = """{"_metadata":"do","action":"Swipe","start":[500,800],"end":[500,200]}"""
    private val backAction = """{"_metadata":"do","action":"Back"}"""
    private val recentAction = """{"action":"press_key","key":"recent"}"""
    private val dragAction = """{"action":"drag","start":[200,500],"end":[800,500],"duration":600}"""
    private val keyEventAction = """{"action":"key_event","key":"notifications"}"""

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

    @Test
    fun swipeFailsWhenPageDoesNotChangeAfterGestureDispatch() {
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
                message = "滑动成功",
                actionJson = swipeAction
            ),
            after = unchanged.copy(timestamp = unchanged.timestamp + 1),
            taskSpec = taskSpec
        )

        assertFalse(result.passed)
        assertTrue(result.reason.contains("滑动后页面内容未变化"))
    }

    @Test
    fun backFailsWhenPageDoesNotChangeAfterGestureDispatch() {
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
                message = "返回成功",
                actionJson = backAction
            ),
            after = unchanged.copy(timestamp = unchanged.timestamp + 1),
            taskSpec = taskSpec
        )

        assertFalse(result.passed)
        assertTrue(result.reason.contains("返回动作 后页面内容未明显变化"))
    }

    @Test
    fun recentAppsPassesWhenSystemViewChanges() {
        val result = verifier.verify(
            before = Observation(
                currentApp = "微信",
                currentPackage = "com.tencent.mm",
                contentItems = listOf(ContentItem(type = "text", text = "通讯录 发现 搜索 服务"))
            ),
            execution = ExecutionResult(
                success = true,
                shouldFinish = false,
                message = "最近任务成功",
                actionJson = recentAction
            ),
            after = Observation(
                currentApp = "系统桌面",
                currentPackage = "com.android.systemui",
                contentItems = listOf(ContentItem(type = "text", text = "最近任务 微信 相册 设置"))
            ),
            taskSpec = taskSpec
        )

        assertTrue(result.passed)
        assertTrue(result.reason, result.reason.contains("最近任务动作"))
    }

    @Test
    fun dragPassesWhenPageContentChanges() {
        val result = verifier.verify(
            before = Observation(
                currentApp = "设置",
                currentPackage = "com.android.settings",
                contentItems = listOf(ContentItem(type = "text", text = "亮度 20%"))
            ),
            execution = ExecutionResult(
                success = true,
                shouldFinish = false,
                message = "拖拽成功",
                actionJson = dragAction
            ),
            after = Observation(
                currentApp = "设置",
                currentPackage = "com.android.settings",
                contentItems = listOf(ContentItem(type = "text", text = "亮度 80%"))
            ),
            taskSpec = taskSpec
        )

        assertTrue(result.passed)
        assertTrue(result.reason, result.reason.contains("拖拽动作"))
    }

    @Test
    fun keyEventPassesWhenSystemPanelChangesPage() {
        val result = verifier.verify(
            before = Observation(
                currentApp = "短信",
                currentPackage = "com.android.mms",
                contentItems = listOf(ContentItem(type = "text", text = "收件箱"))
            ),
            execution = ExecutionResult(
                success = true,
                shouldFinish = false,
                message = "系统按键事件已执行",
                actionJson = keyEventAction
            ),
            after = Observation(
                currentApp = "系统界面",
                currentPackage = "com.android.systemui",
                contentItems = listOf(ContentItem(type = "text", text = "通知 验证码 123456"))
            ),
            taskSpec = taskSpec
        )

        assertTrue(result.passed)
        assertTrue(result.reason, result.reason.contains("系统按键事件"))
    }

    @Test
    fun userInteractionPassesWithoutPostObservation() {
        val result = verifier.verify(
            before = Observation(currentApp = "微信", currentPackage = "com.tencent.mm", contentItems = emptyList()),
            execution = ExecutionResult(
                success = true,
                shouldFinish = false,
                message = "需要用户回答",
                actionJson = """{"_metadata":"do","action":"Ask_User","question":"选择哪个联系人？"}""",
                requiresTakeover = true,
                userInteractionRequest = UserInteractionRequest(
                    question = "选择哪个联系人？",
                    options = listOf("张三 公司", "张三 同学"),
                    reason = "候选项不唯一"
                )
            ),
            after = null,
            taskSpec = taskSpec
        )

        assertTrue(result.passed)
        assertTrue(result.reason.contains("等待用户回答"))
    }

    @Test
    fun clipboardActionPassesWithoutPostObservation() {
        val result = verifier.verify(
            before = Observation(currentApp = "短信", currentPackage = "com.android.mms", contentItems = emptyList()),
            execution = ExecutionResult(
                success = true,
                shouldFinish = false,
                message = "剪贴板内容: 123456",
                actionJson = """{"_metadata":"do","action":"Read_Clipboard","reason":"获取验证码"}""",
                clipboardTrace = ClipboardTrace(
                    operation = ClipboardOperation.READ,
                    success = true,
                    contentPreview = "123456",
                    contentLength = 6,
                    reason = "获取验证码"
                )
            ),
            after = null,
            taskSpec = taskSpec
        )

        assertTrue(result.passed)
        assertTrue(result.reason.contains("剪贴板读取成功"))
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
