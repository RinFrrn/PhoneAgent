package com.mobileagent.phoneagent.harness.trace

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.plan.PlanDecision
import com.mobileagent.phoneagent.harness.recover.FailureType
import com.mobileagent.phoneagent.harness.runtime.StepStatus
import com.mobileagent.phoneagent.model.ContentItem
import com.mobileagent.phoneagent.model.ImageUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TraceStorageStartupTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun startupMigratesLegacyTraceAndHistoryToMinimizedPolicy() {
        val secret = "legacy-private-value"
        val imagePayload = "data:image/png;base64," + "Z".repeat(512)
        val session = SessionTrace(
            sessionId = "legacy",
            taskId = "task-legacy",
            taskGoal = "输入 $secret",
            mode = "HYBRID",
            startedAt = 100L,
            completedAt = 200L,
            success = true,
            outcomeMessage = "已经输入 $secret",
            totalSteps = 1,
            steps = listOf(
                StepTrace(
                    stepIndex = 1,
                    timestamp = 150L,
                    status = StepStatus.EXECUTED,
                    observationBefore = Observation(
                        currentApp = "测试应用",
                        contentItems = listOf(
                            ContentItem(type = "image_url", imageUrl = ImageUrl(imagePayload))
                        )
                    ),
                    decision = PlanDecision(
                        thinking = "输入 $secret",
                        rawResponse = """{"action":"Type","text":"$secret"}""",
                        actionJson = """{"action":"Type","text":"$secret"}"""
                    ),
                    execution = null,
                    observationAfter = null,
                    verification = null
                )
            )
        )
        val traceFile = writeTrace(session)
        writeHistory(
            listOf(
                TaskHistoryEntry(
                    sessionId = session.sessionId,
                    taskId = session.taskId,
                    taskGoal = session.taskGoal,
                    mode = session.mode,
                    startedAt = session.startedAt,
                    completedAt = session.completedAt,
                    status = TaskHistoryStatus.SUCCEEDED,
                    success = true,
                    outcomeMessage = session.outcomeMessage,
                    totalSteps = 1
                )
            )
        )

        val report = TraceStorageStartup.prepare(temporaryFolder.root, now = 300L)
        val migrated = Gson().fromJson(traceFile.readText(), SessionTrace::class.java)
        val history = readHistory().single()

        assertEquals(1, report.migration.scannedTraceCount)
        assertEquals(1, report.migration.migratedTraceCount)
        assertEquals(1, report.migration.migratedHistoryEntryCount)
        assertEquals(TaskHistoryStatus.SUCCEEDED, migrated.status)
        assertEquals(TraceSanitizer.DATA_POLICY, migrated.dataPolicy)
        assertFalse(traceFile.readText().contains(imagePayload))
        assertFalse(traceFile.readText().contains(secret))
        assertFalse(history.taskGoal.contains(secret))
        assertFalse(history.outcomeMessage.orEmpty().contains(secret))

        val secondStartup = TraceStorageStartup.prepare(temporaryFolder.root, now = 400L)
        assertEquals(0, secondStartup.migration.scannedTraceCount)
        assertEquals(0, secondStartup.migration.migratedTraceCount)
    }

    @Test
    fun startupClosesSessionsLeftRunningByPreviousProcess() {
        val store = FileTraceStore(temporaryFolder.root)
        val taskId = "task-1783945075029"
        val sourceSessionId = "5e206830-bea2-4136-9b67-4bcb5e76e90d"
        val sessionId = store.openSession(
            taskId = taskId,
            taskGoal = "打开设置",
            mode = "HYBRID",
            resumedFromSessionId = sourceSessionId,
            resumeStrategy = TraceResumeStrategy.FRESH_OBSERVATION,
            resumedPriorStepCount = 3
        )
        store.appendStep(
            sessionId,
            StepTrace(
                stepIndex = 1,
                timestamp = 100L,
                status = StepStatus.EXECUTED,
                observationBefore = Observation(currentApp = "设置", contentItems = emptyList()),
                decision = null,
                execution = null,
                observationAfter = null,
                verification = null
            )
        )
        val recoveredAt = System.currentTimeMillis() + 1_000L

        val report = TraceStorageStartup.prepare(temporaryFolder.root, now = recoveredAt)
        val history = store.loadRecentHistory().single()
        val trace = store.loadSession(sessionId)

        assertEquals(1, report.recovery.candidateCount)
        assertEquals(1, report.recovery.recoveredCount)
        assertEquals(1, report.recovery.updatedTraceCount)
        assertEquals(TaskHistoryStatus.STOPPED, history.status)
        assertEquals(false, history.success)
        assertEquals(FailureType.RUNTIME_INTERRUPTED, history.failureType)
        assertEquals(recoveredAt, history.completedAt)
        assertEquals(taskId, history.taskId)
        assertEquals(sourceSessionId, history.resumedFromSessionId)
        assertNotNull(trace)
        assertEquals(TaskHistoryStatus.STOPPED, trace?.status)
        assertEquals(FailureType.RUNTIME_INTERRUPTED, trace?.failureType)
        assertEquals(1, trace?.totalSteps)
        assertEquals(taskId, trace?.taskId)
        assertEquals(sourceSessionId, trace?.resumedFromSessionId)
        assertTrue(trace?.outcomeMessage.orEmpty().contains("进程中断"))

        val health = TaskHistoryIndexHealthInspector.inspect(
            temporaryFolder.root,
            now = recoveredAt + 24L * 60L * 60L * 1000L
        )
        assertEquals(0, health.staleRunningCount)
        assertEquals(0, health.missingTraceCount)
    }

    @Test
    fun migrationLeavesUnreadableTraceUntouched() {
        val traceDir = File(temporaryFolder.root, "harness-traces/2026-07-13")
        traceDir.mkdirs()
        val traceFile = File(traceDir, "session-corrupt.json")
        val corruptContent = "{not-valid-json"
        traceFile.writeText(corruptContent)

        val report = TraceStorageStartup.migrateLegacyStorage(temporaryFolder.root)

        assertEquals(1, report.scannedTraceCount)
        assertEquals(0, report.migratedTraceCount)
        assertEquals(1, report.failedTraceCount)
        assertEquals(corruptContent, traceFile.readText())
    }

    private fun writeTrace(session: SessionTrace): File {
        val traceDir = File(temporaryFolder.root, "harness-traces/2026-07-13")
        traceDir.mkdirs()
        return File(traceDir, "session-${session.sessionId}.json").also { file ->
            file.writeText(Gson().toJson(session))
        }
    }

    private fun writeHistory(entries: List<TaskHistoryEntry>) {
        File(temporaryFolder.root, "harness-history.json").writeText(Gson().toJson(entries))
    }

    private fun readHistory(): List<TaskHistoryEntry> {
        val type = object : TypeToken<List<TaskHistoryEntry>>() {}.type
        return Gson().fromJson(File(temporaryFolder.root, "harness-history.json").readText(), type)
    }
}
