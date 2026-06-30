package com.mobileagent.phoneagent.harness.trace

import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.plan.PlanDecision
import com.mobileagent.phoneagent.harness.runtime.StepStatus
import com.mobileagent.phoneagent.model.ModelCallStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelUsageTrendBuilderTest {
    @Test
    fun emptySessionsProduceNoDataReport() {
        val report = ModelUsageTrendBuilder.summarize(emptyList())

        assertTrue(report.isEmpty())
        assertEquals("模型趋势：暂无调用记录", report.toDisplayText())
    }

    @Test
    fun summarizesCallsAcrossRecentSessions() {
        val report = ModelUsageTrendBuilder.summarize(
            listOf(
                session(
                    "s1",
                    stats(
                        latencyMs = 100,
                        requestChars = 1000,
                        totalTokens = 10,
                        providerName = "OPENAI",
                        modelName = "gpt"
                    ),
                    stats(
                        latencyMs = 300,
                        requestChars = 2000,
                        totalTokens = 20,
                        providerName = "OPENAI",
                        modelName = "gpt"
                    )
                ),
                session(
                    "s2",
                    stats(
                        latencyMs = 200,
                        requestChars = 3000,
                        totalTokens = 30,
                        providerName = "GLM",
                        modelName = "glm"
                    )
                )
            )
        )

        assertEquals(2, report.sessionCount)
        assertEquals(2, report.sessionsWithCalls)
        assertEquals(3, report.callCount)
        assertEquals(200, report.averageLatencyMs)
        assertEquals(60, report.totalTokens)
        assertEquals("OPENAI/gpt", report.topModelLabel)
        assertFalse(report.hasWarnings())
        assertTrue(report.toDisplayText().contains("3 次/2 任务"))
    }

    @Test
    fun reportsSlowHeavyAndMissingUsageCalls() {
        val report = ModelUsageTrendBuilder.summarize(
            listOf(
                session(
                    "s1",
                    stats(latencyMs = 12_000, requestChars = 60_000, totalTokens = null)
                )
            )
        )

        assertEquals(1, report.slowCallCount)
        assertEquals(1, report.heavyContextCallCount)
        assertEquals(1, report.missingUsageCallCount)
        assertTrue(report.hasWarnings())
        assertTrue(report.toDisplayText().contains("慢调用 1"))
        assertTrue(report.toDisplayText().contains("上下文偏大 1"))
        assertTrue(report.toDisplayText().contains("缺 usage 1"))
    }

    @Test
    fun summarizesEstimatedCostAcrossRecentSessions() {
        val report = ModelUsageTrendBuilder.summarize(
            listOf(
                session(
                    "s1",
                    stats(
                        latencyMs = 100,
                        requestChars = 1000,
                        totalTokens = 1500,
                        promptTokens = 1000,
                        completionTokens = 500,
                        providerName = "ZHIPU",
                        modelName = "glm-4-plus"
                    )
                ),
                session(
                    "s2",
                    stats(
                        latencyMs = 100,
                        requestChars = 1000,
                        totalTokens = 1000,
                        promptTokens = 500,
                        completionTokens = 500,
                        providerName = "OPENAI",
                        modelName = "unknown"
                    )
                )
            )
        )

        assertEquals(0.075, report.estimatedCostUsd ?: -1.0, 0.000001)
        assertEquals(1, report.unestimatedCostCallCount)
        assertTrue(report.toDisplayText().contains("cost $0.075000"))
        assertTrue(report.detailText().contains("未估算调用: 1"))
    }

    private fun session(sessionId: String, vararg stats: ModelCallStats): SessionTrace {
        return SessionTrace(
            sessionId = sessionId,
            taskId = "task-$sessionId",
            taskGoal = "模型趋势测试",
            mode = "HYBRID",
            startedAt = 1L,
            totalSteps = stats.size,
            steps = stats.mapIndexed { index, stat ->
                StepTrace(
                    stepIndex = index + 1,
                    timestamp = index.toLong(),
                    status = StepStatus.EXECUTED,
                    observationBefore = Observation(currentApp = "测试应用", contentItems = emptyList()),
                    decision = PlanDecision(
                        thinking = "",
                        rawResponse = "",
                        actionJson = """{"_metadata":"do","action":"Wait","duration":"1 seconds"}""",
                        modelCallStats = stat
                    ),
                    execution = null,
                    observationAfter = null,
                    verification = null
                )
            }
        )
    }

    private fun stats(
        latencyMs: Long,
        requestChars: Int,
        totalTokens: Int?,
        providerName: String = "OPENAI",
        modelName: String = "gpt",
        promptTokens: Int? = null,
        completionTokens: Int? = null
    ): ModelCallStats {
        return ModelCallStats(
            providerName = providerName,
            modelName = modelName,
            latencyMs = latencyMs,
            requestChars = requestChars,
            responseChars = 100,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens
        )
    }
}
