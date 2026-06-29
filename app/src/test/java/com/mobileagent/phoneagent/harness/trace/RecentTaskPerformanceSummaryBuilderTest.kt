package com.mobileagent.phoneagent.harness.trace

import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.plan.PlanDecision
import com.mobileagent.phoneagent.harness.runtime.StepStatus
import com.mobileagent.phoneagent.model.ModelCallStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentTaskPerformanceSummaryBuilderTest {
    @Test
    fun summarizesRecentHistoryAndModelCalls() {
        val history = listOf(
            historyEntry("s1", TaskHistoryStatus.SUCCEEDED, totalSteps = 2),
            historyEntry("s2", TaskHistoryStatus.FAILED, totalSteps = 1),
            historyEntry("s3", TaskHistoryStatus.RUNNING, totalSteps = 0)
        )
        val sessions = listOf(
            session(
                "s1",
                listOf(
                    ModelCallStats(
                        providerName = "OPENAI",
                        modelName = "gpt",
                        latencyMs = 120,
                        requestChars = 1000,
                        responseChars = 200,
                        promptTokens = 20,
                        completionTokens = 8,
                        totalTokens = 28
                    )
                )
            ),
            session(
                "s2",
                listOf(
                    ModelCallStats(
                        providerName = "OPENAI",
                        modelName = "gpt",
                        latencyMs = 280,
                        requestChars = 1400,
                        responseChars = 300,
                        promptTokens = 30,
                        completionTokens = 12,
                        totalTokens = 42
                    )
                )
            )
        )

        val summary = RecentTaskPerformanceSummaryBuilder.summarize(history, sessions)

        assertEquals(3, summary.taskCount)
        assertEquals(2, summary.finishedCount)
        assertEquals(1, summary.successCount)
        assertEquals(1, summary.failedCount)
        assertEquals(1, summary.runningCount)
        assertEquals(3, summary.totalSteps)
        assertEquals(2, summary.modelCallSummary.callCount)
        assertEquals(200, summary.modelCallSummary.averageLatencyMs)
        assertEquals(70, summary.modelCallSummary.totalTokens)
        assertTrue(summary.toDisplayText().contains("最近 3 个任务"))
        assertTrue(summary.toDisplayText().contains("成功 1/2"))
        assertTrue(summary.toDisplayText().contains("模型 2 次"))
    }

    @Test
    fun emptyHistoryReturnsEmptySummary() {
        val summary = RecentTaskPerformanceSummaryBuilder.summarize(
            history = emptyList(),
            sessions = emptyList()
        )

        assertTrue(summary.isEmpty())
        assertEquals("最近任务：暂无可统计记录", summary.toDisplayText())
    }

    @Test
    fun missingSessionsStillSummarizesHistory() {
        val summary = RecentTaskPerformanceSummaryBuilder.summarize(
            history = listOf(
                historyEntry("s1", TaskHistoryStatus.RUNNING, totalSteps = 0),
                historyEntry("s2", TaskHistoryStatus.STOPPED, totalSteps = 4)
            ),
            sessions = emptyList()
        )

        assertEquals(2, summary.taskCount)
        assertEquals(1, summary.finishedCount)
        assertEquals(1, summary.stoppedCount)
        assertEquals(1, summary.runningCount)
        assertTrue(summary.modelCallSummary.isEmpty())
        assertTrue(summary.toDisplayText().contains("模型未记录"))
    }

    private fun historyEntry(
        sessionId: String,
        status: TaskHistoryStatus,
        totalSteps: Int
    ): TaskHistoryEntry {
        return TaskHistoryEntry(
            sessionId = sessionId,
            taskId = "task-$sessionId",
            taskGoal = "测试任务",
            mode = "HYBRID",
            startedAt = 1L,
            status = status,
            totalSteps = totalSteps
        )
    }

    private fun session(sessionId: String, stats: List<ModelCallStats>): SessionTrace {
        return SessionTrace(
            sessionId = sessionId,
            taskId = "task-$sessionId",
            taskGoal = "测试任务",
            mode = "HYBRID",
            startedAt = 1L,
            steps = stats.mapIndexed { index, stat ->
                StepTrace(
                    stepIndex = index + 1,
                    timestamp = index.toLong(),
                    status = StepStatus.EXECUTED,
                    observationBefore = Observation(
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
