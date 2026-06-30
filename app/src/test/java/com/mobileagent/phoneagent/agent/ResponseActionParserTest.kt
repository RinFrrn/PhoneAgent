package com.mobileagent.phoneagent.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseActionParserTest {
    private val parser = ResponseActionParser()

    @Test
    fun prefersAnswerTagOverJsonMentionedInThinking() {
        val response = """
            <think>页面里展示了 {"action":"tap","coordinates":[1,1]} 作为文本，不应执行。</think>
            <answer>{"action":"tap","coordinates":[500,600],"reason":"点击确认"}</answer>
        """.trimIndent()

        val json = parser.parseActionFromResponse(response)

        assertTrue(json.contains(""""action":"tap""""))
        assertTrue(json.contains(""""coordinates":[500,600]"""))
        assertFalse(json.contains("[1,1]"))
    }

    @Test
    fun extractsToolCallJson() {
        val response = """
            <thinking>需要打开微信。</thinking>
            <tool_call>{"action":"launch_app","app_name":"微信","reason":"进入目标应用"}</tool_call>
        """.trimIndent()

        val json = parser.parseActionFromResponse(response)

        assertTrue(json.contains(""""action":"launch_app""""))
        assertTrue(json.contains(""""app_name":"微信""""))
    }

    @Test
    fun extractsJsonAfterThinkingWhenToolCallTagIsMissing() {
        val response = """
            <thinking>页面里有 {"action":"tap","coordinates":[1,1]} 只是用户文本。</thinking>
            {"action":"swipe","direction":"up","reason":"继续查找"}
        """.trimIndent()

        val json = parser.parseActionFromResponse(response)

        assertTrue(json.contains(""""action":"swipe""""))
        assertTrue(json.contains(""""direction":"up""""))
        assertFalse(json.contains("[1,1]"))
    }

    @Test
    fun completesMissingClosingBraceInToolCallJson() {
        val response = """
            <thinking>准备点击搜索框。</thinking>
            <tool_call>{"action":"tap","coordinates":[300,200],"reason":"点击搜索框"
        """.trimIndent()

        val json = parser.parseActionFromResponse(response)

        assertTrue(json.contains(""""action":"tap""""))
        assertTrue(json.contains(""""coordinates":[300,200]"""))
        assertTrue(json.trim().endsWith("}"))
    }

    @Test
    fun completesMissingListAndObjectClosersForActionList() {
        val response = """
            {"think":"先输入关键词","action":[{"action":"input_text","text":"咖啡店"}
        """.trimIndent()

        val json = parser.parseActionFromResponse(response)

        assertTrue(json.contains(""""action":"input_text""""))
        assertTrue(json.contains(""""text":"咖啡店""""))
        assertFalse(json.contains(""""think""""))
    }

    @Test
    fun usesSecondBoxAsActionWhenThinkingBoxExists() {
        val response = """
            <|begin_of_box|>先检查通知栏。<|end_of_box|>
            <|begin_of_box|>{"action":"key_event","key":"notifications"}<|end_of_box|>
        """.trimIndent()

        val json = parser.parseActionFromResponse(response)

        assertTrue(json.contains(""""action":"key_event""""))
        assertTrue(json.contains(""""key":"notifications""""))
    }

    @Test
    fun extractsFirstActionFromStructuredActionList() {
        val response = """
            {"think":"需要先点搜索框","action":[{"action":"tap","coordinates":[300,200],"reason":"点击搜索框"}]}
        """.trimIndent()

        val json = parser.parseActionFromResponse(response)

        assertTrue(json.contains(""""action":"tap""""))
        assertTrue(json.contains(""""coordinates":[300,200]"""))
        assertFalse(json.contains(""""think""""))
    }

    @Test
    fun extractsLegacyDoCommandFromStructuredJsonActionString() {
        val response = """
            {"think":"页面加载中，需要等待","action":"do(action=\"Wait\", duration=\"2 seconds\", reason=\"等待稳定\")"}
        """.trimIndent()

        val json = parser.parseActionFromResponse(response)

        assertTrue(json.contains(""""_metadata":"do""""))
        assertTrue(json.contains(""""action":"Wait""""))
        assertFalse(json.contains(""""think""""))
    }

    @Test
    fun extractsLegacyFinishCommandFromStructuredJsonActionString() {
        val response = """
            {"think":"任务完成","action":"finish(message=\"已完成查询\")"}
        """.trimIndent()

        val json = parser.parseActionFromResponse(response)

        assertTrue(json.contains(""""_metadata":"finish""""))
        assertTrue(json.contains(""""message":"已完成查询""""))
        assertFalse(json.contains(""""think""""))
    }

    @Test
    fun normalizesFinishJsonActionToDone() {
        val response = """
            <thinking>任务已完成。</thinking>
            <tool_call>{"action":"finish","success":true,"message":"已完成查询"}</tool_call>
        """.trimIndent()

        val json = parser.parseActionFromResponse(response)

        assertTrue(json.contains(""""action":"done""""))
        assertTrue(json.contains(""""success":true"""))
        assertTrue(json.contains(""""message":"已完成查询""""))
        assertFalse(json.contains(""""action":"finish""""))
    }

    @Test
    fun extractsMultilineActionSection() {
        val response = """
            {think}
            当前页面需要等待加载。
            {action}
            do(action="Wait", duration="2 seconds", reason="等待页面稳定")
        """.trimIndent()

        val json = parser.parseActionFromResponse(response)

        assertTrue(json.contains(""""_metadata":"do""""))
        assertTrue(json.contains(""""action":"Wait""""))
    }
}
