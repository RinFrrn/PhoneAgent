package com.mobileagent.phoneagent.harness.trace

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TaskHistoryMaintenanceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun deleteFinishedEntryRemovesHistoryAndTraceFile() {
        val traceFile = writeTrace("done")
        writeHistory(
            listOf(
                historyEntry("done", TaskHistoryStatus.SUCCEEDED),
                historyEntry("keep", TaskHistoryStatus.FAILED)
            )
        )

        val result = TaskHistoryMaintenance.deleteFinishedEntry(temporaryFolder.root, "done")

        assertEquals(TaskHistoryDeleteStatus.DELETED, result.status)
        assertTrue(result.traceDeleted)
        assertFalse(traceFile.exists())
        assertEquals(listOf("keep"), readHistory().map { it.sessionId })
    }

    @Test
    fun runningEntryIsNotDeleted() {
        writeTrace("running")
        writeHistory(listOf(historyEntry("running", TaskHistoryStatus.RUNNING)))

        val result = TaskHistoryMaintenance.deleteFinishedEntry(temporaryFolder.root, "running")

        assertEquals(TaskHistoryDeleteStatus.RUNNING, result.status)
        assertEquals(listOf("running"), readHistory().map { it.sessionId })
        assertTrue(File(temporaryFolder.root, "harness-traces/2026-06-30/session-running.json").exists())
    }

    @Test
    fun missingTraceStillDeletesFinishedHistoryEntry() {
        writeHistory(listOf(historyEntry("missing", TaskHistoryStatus.STOPPED)))

        val result = TaskHistoryMaintenance.deleteFinishedEntry(temporaryFolder.root, "missing")

        assertEquals(TaskHistoryDeleteStatus.DELETED, result.status)
        assertFalse(result.traceDeleted)
        assertTrue(readHistory().isEmpty())
    }

    @Test
    fun unreadableHistoryDoesNotDeleteAnything() {
        val traceFile = writeTrace("broken")
        File(temporaryFolder.root, "harness-history.json").writeText("not-json")

        val result = TaskHistoryMaintenance.deleteFinishedEntry(temporaryFolder.root, "broken")

        assertEquals(TaskHistoryDeleteStatus.HISTORY_UNREADABLE, result.status)
        assertTrue(traceFile.exists())
    }

    private fun writeTrace(sessionId: String): File {
        val traceDir = File(temporaryFolder.root, "harness-traces/2026-06-30")
        traceDir.mkdirs()
        return File(traceDir, "session-$sessionId.json").apply {
            writeText("{}")
        }
    }

    private fun writeHistory(history: List<TaskHistoryEntry>) {
        File(temporaryFolder.root, "harness-history.json").writeText(Gson().toJson(history))
    }

    private fun readHistory(): List<TaskHistoryEntry> {
        val type = object : TypeToken<List<TaskHistoryEntry>>() {}.type
        return Gson().fromJson(File(temporaryFolder.root, "harness-history.json").readText(), type)
    }

    private fun historyEntry(
        sessionId: String,
        status: TaskHistoryStatus
    ): TaskHistoryEntry {
        return TaskHistoryEntry(
            sessionId = sessionId,
            taskId = "task-$sessionId",
            taskGoal = "任务 $sessionId",
            mode = "HYBRID",
            startedAt = 1L,
            status = status
        )
    }
}
