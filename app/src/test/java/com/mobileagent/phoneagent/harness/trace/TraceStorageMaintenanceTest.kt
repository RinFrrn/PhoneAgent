package com.mobileagent.phoneagent.harness.trace

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TraceStorageMaintenanceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun previewReportsOldOrphanTracesWithoutDeleting() {
        val now = DAYS.toMillis(100)
        val oldOrphan = traceFile("old-orphan", now - DAYS.toMillis(40), "old")
        traceFile("young-orphan", now - DAYS.toMillis(3), "young")
        writeHistory(emptyList())

        val preview = TraceStorageMaintenance.previewOrphanCleanup(
            filesDir = temporaryFolder.root,
            now = now
        )

        assertEquals(1, preview.candidateCount)
        assertEquals(0, preview.deletedCount)
        assertTrue(preview.reclaimedBytes >= 3L)
        assertTrue(preview.dryRun)
        assertTrue(oldOrphan.exists())
    }

    @Test
    fun cleanupDeletesOnlyOldUnreferencedSessionTraces() {
        val now = DAYS.toMillis(100)
        val oldOrphan = traceFile("old-orphan", now - DAYS.toMillis(40), "old")
        val protected = traceFile("protected", now - DAYS.toMillis(45), "protected")
        val youngOrphan = traceFile("young-orphan", now - DAYS.toMillis(5), "young")
        val metadata = File(temporaryFolder.root, "harness-traces/2026-06-30/metadata.json")
        metadata.writeText("{}")
        metadata.setLastModified(now - DAYS.toMillis(45))
        writeHistory(
            listOf(
                TaskHistoryEntry(
                    sessionId = "protected",
                    taskId = "task-protected",
                    taskGoal = "受保护任务",
                    mode = "HYBRID",
                    startedAt = now,
                    status = TaskHistoryStatus.SUCCEEDED
                )
            )
        )

        val result = TraceStorageMaintenance.cleanupOrphanTraces(
            filesDir = temporaryFolder.root,
            now = now
        )

        assertEquals(1, result.candidateCount)
        assertEquals(1, result.deletedCount)
        assertFalse(result.dryRun)
        assertFalse(oldOrphan.exists())
        assertTrue(protected.exists())
        assertTrue(youngOrphan.exists())
        assertTrue(metadata.exists())
    }

    @Test
    fun cleanupPrunesEmptyDateDirectories() {
        val now = DAYS.toMillis(100)
        val oldOrphan = traceFile("old-orphan", now - DAYS.toMillis(40), "old")
        val dateDir = requireNotNull(oldOrphan.parentFile)
        writeHistory(emptyList())

        TraceStorageMaintenance.cleanupOrphanTraces(
            filesDir = temporaryFolder.root,
            now = now
        )

        assertFalse(oldOrphan.exists())
        assertFalse(dateDir.exists())
    }

    private fun traceFile(sessionId: String, modifiedAt: Long, content: String): File {
        val traceDir = File(temporaryFolder.root, "harness-traces/2026-06-30")
        traceDir.mkdirs()
        val file = File(traceDir, "session-$sessionId.json")
        file.writeText(content)
        file.setLastModified(modifiedAt)
        return file
    }

    private fun writeHistory(history: List<TaskHistoryEntry>) {
        File(temporaryFolder.root, "harness-history.json").writeText(Gson().toJson(history))
    }

    private object DAYS {
        fun toMillis(days: Long): Long = days * 24L * 60L * 60L * 1000L
    }
}
