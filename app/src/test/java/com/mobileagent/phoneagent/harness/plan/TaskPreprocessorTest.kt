package com.mobileagent.phoneagent.harness.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPreprocessorTest {
    private val preprocessor = TaskPreprocessor()

    @Test
    fun directLaunchSkipsLlmAndRequestsFinishAfterExecution() {
        val result = preprocessor.preprocess("打开微信")

        assertNotNull(result)
        requireNotNull(result)
        assertJsonField(result.actionJson, "action", "Launch")
        assertJsonField(result.actionJson, "app", "微信")
        assertTrue(result.skipLlm)

        val decision = result.toPlanDecision()
        assertEquals(PlanDecisionSource.TASK_PREPROCESSOR, decision.source)
        assertTrue(decision.finishRequested)
    }

    @Test
    fun compoundLaunchKeepsLlmForFollowUpTask() {
        val result = preprocessor.preprocess("打开微信，然后给张三发消息")

        assertNotNull(result)
        requireNotNull(result)
        assertJsonField(result.actionJson, "action", "Launch")
        assertJsonField(result.actionJson, "app", "微信")
        assertFalse(result.skipLlm)

        val decision = result.toPlanDecision()
        assertFalse(decision.finishRequested)
    }

    @Test
    fun sensitivePaymentTaskAsksUserBeforePlanning() {
        val result = preprocessor.preprocess("打开支付宝，然后给张三转账100元")

        assertNotNull(result)
        requireNotNull(result)
        assertJsonField(result.actionJson, "action", "Ask_User")
        assertTrue(result.actionJson.contains("确认继续"))
        assertTrue(result.actionJson.contains("取消任务"))
        assertTrue(result.skipLlm)

        val decision = result.toPlanDecision()
        assertFalse(decision.finishRequested)
        assertTrue(decision.rawResponse.contains("敏感操作"))
    }

    @Test
    fun sensitiveLoginTaskTakesPriorityOverDirectLaunch() {
        val result = preprocessor.preprocess("打开工商银行登录并查看余额")

        assertNotNull(result)
        requireNotNull(result)
        assertJsonField(result.actionJson, "action", "Ask_User")
        assertFalse(result.toPlanDecision().finishRequested)
    }

    @Test
    fun complexMultiStepTaskCreatesTraceableTodosBeforePlanning() {
        val result = preprocessor.preprocess("帮我查明天北京天气，然后复制摘要，再发给微信好友")

        assertNotNull(result)
        requireNotNull(result)
        assertJsonField(result.actionJson, "action", "Note")
        assertTrue(result.actionJson.contains("- [ ] 帮我查明天北京天气"))
        assertTrue(result.actionJson.contains("- [ ] 复制摘要"))
        assertTrue(result.actionJson.contains("- [ ] 发给微信好友"))
        assertTrue(result.skipLlm)
        assertEquals(PreprocessedTaskType.UI_INTERACTION, result.taskType)

        val decision = result.toPlanDecision()
        assertFalse(decision.finishRequested)
        assertTrue(decision.rawResponse.contains("复杂任务"))
    }

    @Test
    fun compoundLaunchStillLaunchesBeforeTodoPlanning() {
        val result = preprocessor.preprocess("打开微信，然后搜索张三，再发送消息")

        assertNotNull(result)
        requireNotNull(result)
        assertJsonField(result.actionJson, "action", "Launch")
        assertJsonField(result.actionJson, "app", "微信")
        assertFalse(result.toPlanDecision().finishRequested)
    }

    @Test
    fun implicitLaunchFromChineseAppPrefixIsCompound() {
        val result = preprocessor.preprocess("小红书创作一篇图文笔记")

        assertNotNull(result)
        requireNotNull(result)
        assertJsonField(result.actionJson, "action", "Launch")
        assertJsonField(result.actionJson, "app", "小红书")
        assertFalse(result.skipLlm)
    }

    @Test
    fun homeAndBackBecomeDirectSystemActions() {
        val home = requireNotNull(preprocessor.preprocess("返回桌面"))
        val back = requireNotNull(preprocessor.preprocess("返回"))

        assertJsonField(home.actionJson, "action", "Home")
        assertJsonField(back.actionJson, "action", "Back")
        assertTrue(home.skipLlm)
        assertTrue(back.skipLlm)
    }

    @Test
    fun screenSnapshotCommandFinishesWithTraceMessage() {
        val result = preprocessor.preprocess("截图")

        assertNotNull(result)
        requireNotNull(result)
        assertJsonField(result.actionJson, "_metadata", "finish")
        assertJsonField(result.actionJson, "message", "已采集当前屏幕观察并写入本次 Trace；视觉/混合模式包含截图输入，无障碍模式包含结构化屏幕文本。")
        assertTrue(result.skipLlm)
        assertEquals(PreprocessedTaskType.SYSTEM_COMMAND, result.taskType)

        val decision = result.toPlanDecision()
        assertTrue(decision.finishRequested)
        assertTrue(decision.rawResponse.contains("屏幕观察快照"))
    }

    @Test
    fun englishScreenCaptureCommandFinishesWithTraceMessage() {
        val result = preprocessor.preprocess("Screen capture")

        assertNotNull(result)
        requireNotNull(result)
        assertJsonField(result.actionJson, "_metadata", "finish")
        assertTrue(result.skipLlm)
    }

    @Test
    fun unrelatedTaskFallsThroughToLlmPlanner() {
        assertNull(preprocessor.preprocess("帮我看看当前页面应该点哪里"))
    }

    private fun assertJsonField(json: String, key: String, expected: String) {
        val pattern = """"$key"\s*:\s*"([^"]*)"""".toRegex()
        val actual = pattern.find(json)?.groupValues?.getOrNull(1)
        assertEquals(expected, actual)
    }
}
