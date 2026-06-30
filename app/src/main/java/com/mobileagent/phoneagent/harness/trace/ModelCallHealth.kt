package com.mobileagent.phoneagent.harness.trace

enum class ModelCallHealthLevel {
    NO_DATA,
    HEALTHY,
    WATCH,
    SLOW,
    HEAVY
}

data class ModelCallHealthReport(
    val level: ModelCallHealthLevel,
    val summary: ModelCallSummary,
    val warnings: List<String>,
    val recommendation: String
) {
    fun isEmpty(): Boolean = level == ModelCallHealthLevel.NO_DATA

    fun toDisplayText(): String {
        if (isEmpty()) {
            return "模型健康: 未记录"
        }
        val warningText = if (warnings.isEmpty()) {
            "无明显异常"
        } else {
            warnings.joinToString("；")
        }
        return "模型健康: ${level.displayName()} · $warningText"
    }

    fun detailText(): String {
        return buildString {
            appendLine(toDisplayText())
            appendLine("建议: $recommendation")
        }.trimEnd()
    }

    private fun ModelCallHealthLevel.displayName(): String {
        return when (this) {
            ModelCallHealthLevel.NO_DATA -> "未记录"
            ModelCallHealthLevel.HEALTHY -> "健康"
            ModelCallHealthLevel.WATCH -> "观察"
            ModelCallHealthLevel.SLOW -> "偏慢"
            ModelCallHealthLevel.HEAVY -> "负载偏高"
        }
    }
}

object ModelCallHealthAnalyzer {
    fun analyze(summary: ModelCallSummary): ModelCallHealthReport {
        if (summary.isEmpty()) {
            return ModelCallHealthReport(
                level = ModelCallHealthLevel.NO_DATA,
                summary = summary,
                warnings = emptyList(),
                recommendation = "先运行一个包含模型规划的任务，系统会基于调用统计生成建议。"
            )
        }

        val warnings = mutableListOf<String>()
        if (summary.averageLatencyMs >= SLOW_AVERAGE_LATENCY_MS) {
            warnings += "平均延迟 ${summary.averageLatencyMs}ms 偏高"
        }
        if (summary.requestChars >= LARGE_REQUEST_CHARS) {
            warnings += "请求上下文 ${summary.requestChars} 字符偏大"
        }
        if (summary.responseChars >= LARGE_RESPONSE_CHARS) {
            warnings += "响应 ${summary.responseChars} 字符偏大"
        }
        if (summary.totalTokens == null) {
            warnings += "provider 未返回 token usage"
        }

        val level = when {
            summary.requestChars >= VERY_LARGE_REQUEST_CHARS ||
                summary.responseChars >= VERY_LARGE_RESPONSE_CHARS -> ModelCallHealthLevel.HEAVY
            summary.averageLatencyMs >= SLOW_AVERAGE_LATENCY_MS -> ModelCallHealthLevel.SLOW
            warnings.isNotEmpty() -> ModelCallHealthLevel.WATCH
            else -> ModelCallHealthLevel.HEALTHY
        }
        return ModelCallHealthReport(
            level = level,
            summary = summary,
            warnings = warnings,
            recommendation = recommendationFor(level, summary)
        )
    }

    private fun recommendationFor(level: ModelCallHealthLevel, summary: ModelCallSummary): String {
        return when (level) {
            ModelCallHealthLevel.NO_DATA ->
                "先运行一个包含模型规划的任务，系统会基于调用统计生成建议。"
            ModelCallHealthLevel.HEALTHY ->
                "近期模型调用延迟和上下文体量正常。"
            ModelCallHealthLevel.WATCH ->
                if (summary.totalTokens == null) {
                    "当前 provider 没有返回 token usage，成本分析只能依赖字符数和延迟。"
                } else {
                    "建议观察后续调用趋势，必要时打开任务日志检查是否有冗余上下文。"
                }
            ModelCallHealthLevel.SLOW ->
                "模型平均响应偏慢，建议检查网络、provider 状态，或减少截图/历史上下文。"
            ModelCallHealthLevel.HEAVY ->
                "上下文或响应体量偏大，建议减少历史消息、压缩页面内容或拆分任务目标。"
        }
    }

    private const val SLOW_AVERAGE_LATENCY_MS = 10_000L
    private const val LARGE_REQUEST_CHARS = 50_000
    private const val VERY_LARGE_REQUEST_CHARS = 120_000
    private const val LARGE_RESPONSE_CHARS = 12_000
    private const val VERY_LARGE_RESPONSE_CHARS = 30_000
}
