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
    fun unrelatedTaskFallsThroughToLlmPlanner() {
        assertNull(preprocessor.preprocess("帮我看看当前页面应该点哪里"))
    }

    private fun assertJsonField(json: String, key: String, expected: String) {
        val pattern = """"$key"\s*:\s*"([^"]*)"""".toRegex()
        val actual = pattern.find(json)?.groupValues?.getOrNull(1)
        assertEquals(expected, actual)
    }
}
