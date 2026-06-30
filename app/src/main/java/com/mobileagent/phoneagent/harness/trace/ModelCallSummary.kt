package com.mobileagent.phoneagent.harness.trace

data class ModelCallSummary(
    val callCount: Int,
    val totalLatencyMs: Long,
    val averageLatencyMs: Long,
    val requestChars: Int,
    val responseChars: Int,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val estimatedCostUsd: Double? = null,
    val unestimatedCostCallCount: Int = 0
) {
    fun isEmpty(): Boolean = callCount == 0

    fun toDisplayText(): String {
        if (isEmpty()) {
            return "模型调用: 未记录"
        }
        val tokenText = totalTokens?.let { total ->
            "tokens=$total" +
                (promptTokens?.let { ", prompt=$it" } ?: "") +
                (completionTokens?.let { ", completion=$it" } ?: "")
        } ?: "tokens=unknown"
        val costText = estimatedCostUsd?.let { cost ->
            " · 估算成本 ${ModelCostEstimator.formatUsd(cost)}" +
                if (unestimatedCostCallCount > 0) "（$unestimatedCostCallCount 次未估算）" else ""
        } ?: if (unestimatedCostCallCount > 0) {
            " · 成本未估算 $unestimatedCostCallCount 次"
        } else {
            ""
        }
        return "模型调用: $callCount 次 · 总耗时 ${totalLatencyMs}ms · 平均 ${averageLatencyMs}ms · " +
            "请求 ${requestChars} 字符 · 响应 ${responseChars} 字符 · $tokenText$costText"
    }
}

object ModelCallSummaryBuilder {
    fun summarize(session: SessionTrace): ModelCallSummary {
        val stats = session.steps.mapNotNull { it.decision?.modelCallStats }
        if (stats.isEmpty()) {
            return ModelCallSummary(
                callCount = 0,
                totalLatencyMs = 0L,
                averageLatencyMs = 0L,
                requestChars = 0,
                responseChars = 0
            )
        }

        val totalLatency = stats.sumOf { it.latencyMs }
        val promptTokens = sumNullable(stats.map { it.promptTokens })
        val completionTokens = sumNullable(stats.map { it.completionTokens })
        val totalTokens = sumNullable(stats.map { it.totalTokens })
        val estimates = stats.map { ModelCostEstimator.estimate(it) }
        val estimatedCosts = estimates.filterNotNull()
        return ModelCallSummary(
            callCount = stats.size,
            totalLatencyMs = totalLatency,
            averageLatencyMs = totalLatency / stats.size,
            requestChars = stats.sumOf { it.requestChars },
            responseChars = stats.sumOf { it.responseChars },
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            estimatedCostUsd = estimatedCosts.takeIf { it.isNotEmpty() }?.sumOf { it.costUsd },
            unestimatedCostCallCount = estimates.count { it == null }
        )
    }

    private fun sumNullable(values: List<Int?>): Int? {
        val present = values.filterNotNull()
        if (present.isEmpty()) {
            return null
        }
        return present.sum()
    }
}
