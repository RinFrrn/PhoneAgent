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
