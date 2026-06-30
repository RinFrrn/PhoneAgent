package com.mobileagent.phoneagent.harness.runtime

import com.mobileagent.phoneagent.agent.Mode
import com.mobileagent.phoneagent.harness.act.ExecutionHumanizationProfile
import com.mobileagent.phoneagent.harness.trace.ModelCallSummary
import com.mobileagent.phoneagent.harness.trace.ModelUsageTrendReport
import com.mobileagent.phoneagent.harness.trace.RecentTaskPerformanceSummary
import com.mobileagent.phoneagent.harness.trace.TaskHistoryEntry
import com.mobileagent.phoneagent.harness.trace.TaskHistoryStatus
import com.mobileagent.phoneagent.harness.trace.TraceStorageReport
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

    @Test
    fun traceStorageWarningsAppearInDiagnosticText() {
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
            recentSummary = emptyRecentSummary(),
            history = emptyList(),
            traceStorageReport = TraceStorageReport(
                traceFileCount = 501,
                historyCount = 190,
                totalBytes = 60L * 1024L * 1024L,
                largestTraceBytes = 1024L,
                oldestTraceAt = 1L,
                newestTraceAt = 2L,
                historyReadable = true,
                warnings = listOf("Trace 文件较多，建议导出后清理旧任务")
            ),
            generatedAt = 1L
        )

        assertTrue(snapshot.compactText().contains("Trace需关注"))
        assertTrue(snapshot.detailText().contains("Trace 存储: 501 个文件"))
        assertTrue(snapshot.detailText().contains("Trace 文件较多"))
    }

    @Test
    fun deviceHealthWarningsAppearInDiagnosticText() {
        val deviceSnapshot = RuntimeDeviceSnapshot(
            manufacturer = "Google",
            model = "Pixel",
            androidVersion = "15",
            sdkInt = 35,
            screenResolution = "1080x2400",
            batteryPercent = 8,
            charging = false,
            interactive = true,
            keyguardLocked = true,
            powerSaveMode = true
        )
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
                humanizationEnabled = false,
                deviceSnapshot = deviceSnapshot
            ),
            humanizationProfile = ExecutionHumanizationProfile(),
            recentSummary = emptyRecentSummary(),
            history = emptyList(),
            deviceSnapshot = deviceSnapshot,
            generatedAt = 1L
        )

        assertTrue(snapshot.compactText().contains("设备锁屏"))
        assertTrue(snapshot.detailText().contains("设备提示: 设备处于锁屏状态"))
        assertTrue(snapshot.detailText().contains("设备提示: 设备处于省电模式"))
    }

    @Test
    fun diagnosticDetailIncludesModelUsageTrend() {
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
            recentSummary = emptyRecentSummary(),
            history = emptyList(),
            modelUsageTrend = ModelUsageTrendReport(
                sessionCount = 2,
                sessionsWithCalls = 1,
                callCount = 3,
                averageLatencyMs = 1200L,
                slowCallCount = 0,
                heavyContextCallCount = 0,
                missingUsageCallCount = 1,
                totalTokens = 90,
                topModelLabel = "OPENAI/gpt"
            ),
            generatedAt = 1L
        )

        assertTrue(snapshot.detailText().contains("模型趋势：3 次/1 任务"))
        assertTrue(snapshot.detailText().contains("缺 usage: 1"))
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
