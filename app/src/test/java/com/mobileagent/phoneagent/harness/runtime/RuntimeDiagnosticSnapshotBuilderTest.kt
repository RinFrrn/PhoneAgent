package com.mobileagent.phoneagent.harness.runtime

import com.mobileagent.phoneagent.agent.Mode
import com.mobileagent.phoneagent.harness.act.ExecutionHumanizationProfile
import com.mobileagent.phoneagent.harness.trace.ModelCallSummary
import com.mobileagent.phoneagent.harness.trace.RecentTaskPerformanceSummary
import com.mobileagent.phoneagent.harness.trace.TaskHistoryEntry
import com.mobileagent.phoneagent.harness.trace.TaskHistoryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDiagnosticSnapshotBuilderTest {
    @Test
    fun blockersProduceBlockedDiagnostic() {
        val snapshot = RuntimeDiagnosticSnapshotBuilder.build(
            running = false,
            mode = Mode.ACCESSIBILITY,
            modelLabel = "GLM · glm-4.5v",
            readiness = RunReadinessChecker.evaluate(
                modelConfigured = false,
                accessibilityEnabled = false,
                overlayEnabled = true,
                notificationEnabled = true,
                mode = Mode.ACCESSIBILITY,
                screenCaptureReady = true,
                humanizationEnabled = false
            ),
            humanizationProfile = ExecutionHumanizationProfile(),
            recentSummary = emptyRecentSummary(),
            history = emptyList(),
            generatedAt = 1L
        )

        assertEquals(RuntimeDiagnosticLevel.BLOCKED, snapshot.level)
        assertTrue(snapshot.compactText().contains("阻塞 2"))
        assertTrue(snapshot.detailText().contains("模型未配置"))
    }

    @Test
    fun runningDiagnosticOverridesWarnings() {
        val snapshot = RuntimeDiagnosticSnapshotBuilder.build(
            running = true,
            mode = Mode.HYBRID,
            modelLabel = "OpenAI · gpt",
            readiness = RunReadinessChecker.evaluate(
                modelConfigured = true,
                accessibilityEnabled = true,
                overlayEnabled = true,
                notificationEnabled = true,
                mode = Mode.HYBRID,
                screenCaptureReady = false,
                humanizationEnabled = true
            ),
            humanizationProfile = ExecutionHumanizationProfile(enabled = true),
            recentSummary = recentSummary(failedCount = 0),
            history = emptyList(),
            generatedAt = 1L
        )

        assertEquals(RuntimeDiagnosticLevel.RUNNING, snapshot.level)
        assertTrue(snapshot.compactText().contains("运行中"))
        assertTrue(snapshot.detailText().contains("执行拟真: 已启用"))
    }

    @Test
    fun recentFailuresDegradeOtherwiseReadyDiagnostic() {
        val snapshot = RuntimeDiagnosticSnapshotBuilder.build(
            running = false,
            mode = Mode.ACCESSIBILITY,
            modelLabel = "OpenAI · gpt",
            readiness = RunReadinessChecker.evaluate(
                modelConfigured = true,
                accessibilityEnabled = true,
                overlayEnabled = true,
                notificationEnabled = true,
                mode = Mode.ACCESSIBILITY,
                screenCaptureReady = true,
                humanizationEnabled = false
            ),
            humanizationProfile = ExecutionHumanizationProfile(),
            recentSummary = recentSummary(failedCount = 1),
            history = listOf(
                TaskHistoryEntry(
                    sessionId = "s1",
                    taskId = "task-s1",
                    taskGoal = "测试任务",
                    mode = "HYBRID",
                    startedAt = 2L,
                    status = TaskHistoryStatus.FAILED,
                    totalSteps = 3
                )
            ),
            generatedAt = 1L
        )

        assertEquals(RuntimeDiagnosticLevel.DEGRADED, snapshot.level)
        assertTrue(snapshot.compactText().contains("需关注"))
        assertTrue(snapshot.detailText().contains("最新历史: 测试任务 / FAILED"))
    }

    private fun emptyRecentSummary(): RecentTaskPerformanceSummary {
        return RecentTaskPerformanceSummary(
            taskCount = 0,
            finishedCount = 0,
            successCount = 0,
            failedCount = 0,
            stoppedCount = 0,
            runningCount = 0,
            totalSteps = 0,
            modelCallSummary = ModelCallSummary(
                callCount = 0,
                totalLatencyMs = 0L,
                averageLatencyMs = 0L,
                requestChars = 0,
                responseChars = 0
            )
        )
    }

    private fun recentSummary(failedCount: Int): RecentTaskPerformanceSummary {
        return RecentTaskPerformanceSummary(
            taskCount = 2,
            finishedCount = 2,
            successCount = 2 - failedCount,
            failedCount = failedCount,
            stoppedCount = 0,
            runningCount = 0,
            totalSteps = 5,
            modelCallSummary = ModelCallSummary(
                callCount = 2,
                totalLatencyMs = 400L,
                averageLatencyMs = 200L,
                requestChars = 1000,
                responseChars = 400,
                totalTokens = 80
            )
        )
    }
}
