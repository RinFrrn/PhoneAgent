package com.mobileagent.phoneagent.harness.trace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskTraceExportBuilderTest {
    @Test
    fun buildsSanitizedTraceExportText() {
        val text = TaskTraceExportBuilder.buildText(
            title = "任务日志",
            summary = "任务: 发送给 13812345678",
            log = "模型输出 api_key=sk-1234567890abcdefghijklmnopqrstuvwxyz"
        )

        assertTrue(text.contains("========== 摘要 =========="))
        assertTrue(text.contains("========== 完整输出过程 =========="))
        assertTrue(text.contains("138****5678"))
        assertTrue(text.contains("api_key="))
        assertTrue(text.contains("***"))
        assertFalse(text.contains("13812345678"))
        assertFalse(text.contains("1234567890abcdefghijklmnopqrstuvwxyz"))
    }
}
