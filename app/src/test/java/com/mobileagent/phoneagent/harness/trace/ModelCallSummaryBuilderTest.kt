package com.mobileagent.phoneagent.harness.trace

import com.mobileagent.phoneagent.harness.plan.PlanDecision
import com.mobileagent.phoneagent.harness.runtime.StepStatus
import com.mobileagent.phoneagent.model.ModelCallStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCallSummaryBuilderTest {
    @Test
    fun summarizesModelCallsAcrossSessionSteps() {
        val session = session(
            stats = listOf(
                ModelCallStats(
                    providerName = "OPENAI",
                    modelName = "m1",
                    latencyMs = 100,
                    requestChars = 1000,
                    responseChars = 200,
                    promptTokens = 10,
                    completionTokens = 5,
                    totalTokens = 15
                ),
                ModelCallStats(
                    providerName = "OPENAI",
                    modelName = "m1",
                    latencyMs = 300,
                    requestChars = 1500,
                    responseChars = 500,
                    promptTokens = 20,
                    completionTokens = 7,
                    totalTokens = 27
                )
            )
        )

        val summary = ModelCallSummaryBuilder.summarize(session)

        assertEquals(2, summary.callCount)
        assertEquals(400, summary.totalLatencyMs)
        assertEquals(200, summary.averageLatencyMs)
        assertEquals(2500, summary.requestChars)
        assertEquals(700, summary.responseChars)
        assertEquals(30, summary.promptTokens)
        assertEquals(12, summary.completionTokens)
        assertEquals(42, summary.totalTokens)
        assertTrue(summary.toDisplayText().contains("2 次"))
    }

    @Test
    fun missingTokenUsageRemainsUnknown() {
        val session = session(
            stats = listOf(
                ModelCallStats(
                    providerName = "OLLAMA",
                    modelName = "local",
                    latencyMs = 50,
                    requestChars = 100,
                    responseChars = 20
                )
            )
        )

        val summary = ModelCallSummaryBuilder.summarize(session)

        assertEquals(1, summary.callCount)
        assertNull(summary.totalTokens)
        assertTrue(summary.toDisplayText().contains("tokens=unknown"))
    }

    @Test
    fun emptySessionReturnsEmptySummary() {
        val summary = ModelCallSummaryBuilder.summarize(session(stats = emptyList()))

        assertTrue(summary.isEmpty())
        assertEquals("模型调用: 未记录", summary.toDisplayText())
    }

    private fun session(stats: List<ModelCallStats>): SessionTrace {
        return SessionTrace(
            sessionId = "s1",
            taskId = "t1",
            taskGoal = "测试任务",
            mode = "ACCESSIBILITY",
            startedAt = 1L,
            steps = stats.mapIndexed { index, stat ->
                StepTrace(
                    stepIndex = index + 1,
                    timestamp = index.toLong(),
                    status = StepStatus.EXECUTED,
                    observationBefore = com.mobileagent.phoneagent.harness.observe.Observation(
                        currentApp = "测试应用",
                        contentItems = emptyList()
                    ),
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
}
