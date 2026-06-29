package com.mobileagent.phoneagent.harness.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskTraceSearchTest {
    @Test
    fun blankQueryReturnsFullLog() {
        val result = TaskTraceSearch.filter("line 1\nline 2", "")

        assertFalse(result.filtered)
        assertEquals("line 1\nline 2", result.displayText)
        assertEquals("显示完整日志，共 2 行", result.statusText())
    }

    @Test
    fun queryReturnsMatchingLinesWithContext() {
        val log = listOf(
            "step 1",
            "模型输出",
            "失败类型: ACTION_NOT_EFFECTIVE",
            "验证结果",
            "step 2"
        ).joinToString("\n")

        val result = TaskTraceSearch.filter(log, "action_not_effective")

        assertTrue(result.filtered)
        assertEquals(1, result.matchCount)
        assertTrue(result.displayText.contains("  2: 模型输出"))
        assertTrue(result.displayText.contains("> 3: 失败类型: ACTION_NOT_EFFECTIVE"))
        assertTrue(result.displayText.contains("  4: 验证结果"))
        assertFalse(result.displayText.contains("step 1"))
    }

    @Test
    fun noMatchShowsEmptyResultMessage() {
        val result = TaskTraceSearch.filter("模型输出\n执行结果", "不存在")

        assertEquals(0, result.matchCount)
        assertTrue(result.displayText.contains("未找到匹配内容"))
        assertEquals("未找到：不存在", result.statusText())
    }
}
