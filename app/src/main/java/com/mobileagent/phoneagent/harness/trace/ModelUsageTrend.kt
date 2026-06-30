package com.mobileagent.phoneagent.harness.trace

data class ModelUsageTrendReport(
    val sessionCount: Int,
    val sessionsWithCalls: Int,
    val callCount: Int,
    val averageLatencyMs: Long,
    val slowCallCount: Int,
    val heavyContextCallCount: Int,
    val missingUsageCallCount: Int,
    val totalTokens: Int?,
    val topModelLabel: String?,
    val estimatedCostUsd: Double? = null,
    val unestimatedCostCallCount: Int = 0
) {
    fun isEmpty(): Boolean = callCount == 0

    fun hasWarnings(): Boolean {
        return slowCallCount > 0 || heavyContextCallCount > 0 || missingUsageCallCount > 0
    }

    fun toDisplayText(): String {
        if (isEmpty()) {
            return "模型趋势：暂无调用记录"
        }
        val tokenText = totalTokens?.let { " · tokens $it" } ?: " · tokens unknown"
        val costText = estimatedCostUsd?.let { cost ->
            " · cost ${ModelCostEstimator.formatUsd(cost)}" +
                if (unestimatedCostCallCount > 0) " ($unestimatedCostCallCount unestimated)" else ""
        } ?: if (unestimatedCostCallCount > 0) {
            " · cost unknown $unestimatedCostCallCount"
        } else {
            ""
        }
        val modelText = topModelLabel?.let { " · 主模型 $it" }.orEmpty()
        val warningText = warnings().takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = " · ", separator = " · ")
            .orEmpty()
        return "模型趋势：$callCount 次/$sessionsWithCalls 任务 · 均耗 ${averageLatencyMs}ms$tokenText$costText$modelText$warningText"
    }

    fun detailText(): String {
        return buildString {
            appendLine(toDisplayText())
            appendLine("统计任务: $sessionCount 个，含模型调用 $sessionsWithCalls 个")
            appendLine("慢调用: $slowCallCount，重上下文: $heavyContextCallCount，缺 usage: $missingUsageCallCount")
            estimatedCostUsd?.let { appendLine("估算成本: ${ModelCostEstimator.formatUsd(it)}，未估算调用: $unestimatedCostCallCount") }
        }.trimEnd()
    }

    private fun warnings(): List<String> {
        val warnings = mutableListOf<String>()
        if (slowCallCount > 0) {
            warnings += "慢调用 $slowCallCount"
        }
        if (heavyContextCallCount > 0) {
            warnings += "上下文偏大 $heavyContextCallCount"
        }
        if (missingUsageCallCount > 0) {
            warnings += "缺 usage $missingUsageCallCount"
        }
        return warnings
    }
}

object ModelUsageTrendBuilder {
    fun summarize(sessions: List<SessionTrace>): ModelUsageTrendReport {
        val calls = sessions.flatMap { session ->
            session.steps.mapNotNull { step -> step.decision?.modelCallStats }
        }
        if (calls.isEmpty()) {
            return ModelUsageTrendReport(
                sessionCount = sessions.size,
                sessionsWithCalls = 0,
                callCount = 0,
                averageLatencyMs = 0L,
                slowCallCount = 0,
                heavyContextCallCount = 0,
                missingUsageCallCount = 0,
                totalTokens = null,
                topModelLabel = null
            )
        }

        val sessionsWithCalls = sessions.count { session ->
            session.steps.any { step -> step.decision?.modelCallStats != null }
        }
        val estimates = calls.map { ModelCostEstimator.estimate(it) }
        val estimatedCosts = estimates.filterNotNull()
        return ModelUsageTrendReport(
            sessionCount = sessions.size,
            sessionsWithCalls = sessionsWithCalls,
            callCount = calls.size,
            averageLatencyMs = calls.sumOf { it.latencyMs } / calls.size,
            slowCallCount = calls.count { it.latencyMs >= SLOW_CALL_MS },
            heavyContextCallCount = calls.count { it.requestChars >= HEAVY_REQUEST_CHARS },
            missingUsageCallCount = calls.count { it.totalTokens == null },
            totalTokens = sumNullable(calls.map { it.totalTokens }),
            topModelLabel = topModel(calls.map { "${it.providerName}/${it.modelName}" }),
            estimatedCostUsd = estimatedCosts.takeIf { it.isNotEmpty() }?.sumOf { it.costUsd },
            unestimatedCostCallCount = estimates.count { it == null }
        )
    }

    private fun topModel(labels: List<String>): String? {
        return labels
            .groupingBy { it }
            .eachCount()
            .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            ?.key
    }

    private fun sumNullable(values: List<Int?>): Int? {
        val present = values.filterNotNull()
        if (present.isEmpty()) {
            return null
        }
        return present.sum()
    }

    private const val SLOW_CALL_MS = 10_000L
    private const val HEAVY_REQUEST_CHARS = 50_000
}
