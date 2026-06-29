package com.mobileagent.phoneagent.harness.trace

import com.mobileagent.phoneagent.utils.LogSanitizer

object TaskTraceExportBuilder {
    fun buildText(
        title: String,
        summary: String,
        log: String
    ): String {
        val raw = buildString {
            appendLine(title.ifBlank { "PhoneAgent 任务日志" })
            appendLine()
            appendLine("========== 摘要 ==========")
            appendLine(summary.ifBlank { "无摘要" })
            appendLine()
            appendLine("========== 完整输出过程 ==========")
            appendLine(log.ifBlank { "暂无完整输出过程。" })
        }.trimEnd()
        return LogSanitizer.sanitize(raw)
    }
}
