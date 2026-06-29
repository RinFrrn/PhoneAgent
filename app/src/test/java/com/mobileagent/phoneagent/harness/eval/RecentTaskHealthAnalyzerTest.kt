package com.mobileagent.phoneagent.harness.eval

import com.mobileagent.phoneagent.harness.recover.FailureType
import com.mobileagent.phoneagent.harness.trace.TaskHistoryEntry
import com.mobileagent.phoneagent.harness.trace.TaskHistoryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentTaskHealthAnalyzerTest {
    @Test
    fun emptyHistoryReturnsNoDataReport() {
        val report = RecentTaskHealthAnalyzer.analyze(emptyList())

        assertEquals(RecentTaskHealthLevel.NO_DATA, report.level)
        assertTrue(report.isEmpty())
        assertEquals("任务健康：暂无历史数据", report.toDisplayText())
    }

    @Test
    fun allSuccessfulHistoryIsHealthy() {
        val report = RecentTaskHealthAnalyzer.analyze(
            listOf(
                entry("s1", TaskHistoryStatus.SUCCEEDED, totalSteps = 2),
                entry("s2", TaskHistoryStatus.SUCCEEDED, totalSteps = 4)
            )
        )

        assertEquals(RecentTaskHealthLevel.HEALTHY, report.level)
        assertEquals(100, report.successRatePercent)
        assertEquals(3f, report.averageSteps)
        assertNull(report.dominantFailureType)
        assertTrue(report.toDisplayText().contains("成功率 100%"))
    }

    @Test
    fun repeatedFailuresAreFailingWithDominantFailureRecommendation() {
        val report = RecentTaskHealthAnalyzer.analyze(
            listOf(
                entry("s1", TaskHistoryStatus.FAILED, FailureType.ACTION_NOT_EFFECTIVE, totalSteps = 6, startedAt = 1L),
                entry("s2", TaskHistoryStatus.FAILED, FailureType.ACTION_NOT_EFFECTIVE, totalSteps = 5, startedAt = 2L),
                entry("s3", TaskHistoryStatus.SUCCEEDED, totalSteps = 3, startedAt = 3L)
            )
        )

        assertEquals(RecentTaskHealthLevel.FAILING, report.level)
        assertEquals(33, report.successRatePercent)
        assertEquals(FailureType.ACTION_NOT_EFFECTIVE, report.dominantFailureType)
        assertEquals(2, report.dominantFailureCount)
        assertTrue(report.recommendation.contains("动作未生效"))
        assertTrue(report.detailText().contains("最近失败"))
    }

    @Test
    fun runningOnlyHistoryIsBusy() {
        val report = RecentTaskHealthAnalyzer.analyze(
            listOf(entry("s1", TaskHistoryStatus.RUNNING, totalSteps = 0))
        )

        assertEquals(RecentTaskHealthLevel.BUSY, report.level)
        assertNull(report.successRatePercent)
        assertTrue(report.toDisplayText().contains("暂无完成"))
        assertTrue(report.recommendation.contains("运行中"))
    }

    @Test
    fun singleFailureDowngradesToWatch() {
        val report = RecentTaskHealthAnalyzer.analyze(
            listOf(
                entry("s1", TaskHistoryStatus.SUCCEEDED, totalSteps = 2),
                entry("s2", TaskHistoryStatus.FAILED, FailureType.MODEL_AUTH, totalSteps = 1)
            )
        )

        assertEquals(RecentTaskHealthLevel.WATCH, report.level)
        assertEquals(FailureType.MODEL_AUTH, report.dominantFailureType)
        assertTrue(report.recommendation.contains("模型请求"))
    }

    private fun entry(
        sessionId: String,
        status: TaskHistoryStatus,
        failureType: FailureType? = null,
        totalSteps: Int,
        startedAt: Long = 1L
    ): TaskHistoryEntry {
        return TaskHistoryEntry(
            sessionId = sessionId,
            taskId = "task-$sessionId",
            taskGoal = "测试任务 $sessionId",
            mode = "HYBRID",
            startedAt = startedAt,
            status = status,
            totalSteps = totalSteps,
            failureType = failureType,
            outcomeMessage = failureType?.name
        )
    }
}
