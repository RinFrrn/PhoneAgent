package com.mobileagent.phoneagent.harness.trace

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TaskHistoryIndexHealthInspectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun emptyHistoryIsHealthyAndVisible() {
        val report = TaskHistoryIndexHealthInspector.inspect(temporaryFolder.root)

        assertEquals(0, report.totalCount)
        assertFalse(report.hasIssues())
        assertEquals("历史索引：暂无记录", report.toDisplayText())
    }

    @Test
    fun reportsMissingTraceForFinishedHistoryEntry() {
        writeHistory(
            listOf(
                historyEntry("missing", TaskHistoryStatus.SUCCEEDED, startedAt = 10L),
                historyEntry("present", TaskHistoryStatus.FAILED, startedAt = 11L)
            )
        )
        writeTrace("present")

        val report = TaskHistoryIndexHealthInspector.inspect(temporaryFolder.root, now = 20L)

        assertEquals(2, report.totalCount)
        assertEquals(1, report.missingTraceCount)
        assertEquals(0, report.staleRunningCount)
        assertTrue(report.toDisplayText().contains("trace 缺失 1"))
        assertTrue(report.issueFor(historyEntry("missing", TaskHistoryStatus.SUCCEEDED))?.hasIssues() == true)
        assertTrue(
            report.issueFor(historyEntry("missing", TaskHistoryStatus.SUCCEEDED))
                ?.toDisplaySuffix()
                ?.contains("trace 缺失") == true
        )
    }

    @Test
    fun reportsStaleRunningWithoutRequiringTrace() {
        val now = HOURS.toMillis(10)
        writeHistory(
            listOf(
                historyEntry("stale", TaskHistoryStatus.RUNNING, startedAt = now - HOURS.toMillis(8)),
                historyEntry("fresh", TaskHistoryStatus.RUNNING, startedAt = now - HOURS.toMillis(1))
            )
        )

        val report = TaskHistoryIndexHealthInspector.inspect(temporaryFolder.root, now = now)

        assertEquals(0, report.missingTraceCount)
        assertEquals(1, report.staleRunningCount)
        assertTrue(report.toDisplayText().contains("运行超时 1"))
        assertTrue(
            report.issueFor(historyEntry("stale", TaskHistoryStatus.RUNNING))
                ?.toDisplaySuffix()
                ?.contains("运行超时") == true
        )
    }

    @Test
    fun unreadableHistoryIsReported() {
        File(temporaryFolder.root, "harness-history.json").writeText("not-json")

        val report = TaskHistoryIndexHealthInspector.inspect(temporaryFolder.root)

        assertFalse(report.historyReadable)
        assertTrue(report.hasIssues())
        assertEquals("历史索引：读取失败", report.toDisplayText())
    }

    private fun writeHistory(history: List<TaskHistoryEntry>) {
        File(temporaryFolder.root, "harness-history.json").writeText(Gson().toJson(history))
    }

    private fun writeTrace(sessionId: String) {
        val traceDir = File(temporaryFolder.root, "harness-traces/2026-06-30")
        traceDir.mkdirs()
        File(traceDir, "session-$sessionId.json").writeText("{}")
    }

    private fun historyEntry(
        sessionId: String,
        status: TaskHistoryStatus,
        startedAt: Long = 1L
    ): TaskHistoryEntry {
        return TaskHistoryEntry(
            sessionId = sessionId,
            taskId = "task-$sessionId",
            taskGoal = "任务 $sessionId",
            mode = "HYBRID",
            startedAt = startedAt,
            status = status
        )
    }

    private object HOURS {
        fun toMillis(hours: Long): Long = hours * 60L * 60L * 1000L
    }
}
