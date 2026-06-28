package com.mobileagent.phoneagent.harness.trace

import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.recover.FailureType
import com.mobileagent.phoneagent.harness.runtime.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileTraceStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun openSessionWritesRunningHistoryWithTaskAndModel() {
        val store = FileTraceStore(temporaryFolder.root)

        val sessionId = store.openSession(
            taskId = "task-1",
            taskGoal = "打开微信",
            mode = "HYBRID",
            modelProvider = "OPENAI",
            modelDisplayName = "主模型",
            modelName = "gpt-4o",
            modelBaseUrl = "https://api.openai.com/v1"
        )

        val history = store.loadRecentHistory(limit = 5)
        assertEquals(1, history.size)
        assertEquals(sessionId, history.first().sessionId)
        assertEquals("打开微信", history.first().taskGoal)
        assertEquals("HYBRID", history.first().mode)
        assertEquals("OPENAI", history.first().modelProvider)
        assertEquals("主模型", history.first().modelDisplayName)
        assertEquals("gpt-4o", history.first().modelName)
        assertEquals("https://api.openai.com/v1", history.first().modelBaseUrl)
        assertEquals(TaskHistoryStatus.RUNNING, history.first().status)
    }

    @Test
    fun closeSessionUpdatesHistoryAndWritesTraceSummary() {
        val store = FileTraceStore(temporaryFolder.root)
        val sessionId = store.openSession(
            taskId = "task-2",
            taskGoal = "打开设置",
            mode = "ACCESSIBILITY",
            modelProvider = "GLM",
            modelDisplayName = "GLM (智谱AI)",
            modelName = "glm-4.5v",
            modelBaseUrl = "https://open.bigmodel.cn/api/paas/v4"
        )

        store.appendStep(
            sessionId,
            StepTrace(
                stepIndex = 1,
                timestamp = 123L,
                status = StepStatus.EXECUTED,
                observationBefore = Observation(currentApp = "Phone Agent", contentItems = emptyList()),
                decision = null,
                execution = null,
                observationAfter = null,
                verification = null
            )
        )
        store.closeSession(
            sessionId = sessionId,
            status = TaskHistoryStatus.FAILED,
            outcomeMessage = "验证失败",
            failureType = FailureType.VERIFICATION_FAILED
        )

        val entry = store.loadRecentHistory(limit = 5).first()
        assertEquals(TaskHistoryStatus.FAILED, entry.status)
        assertEquals(false, entry.success)
        assertEquals("验证失败", entry.outcomeMessage)
        assertEquals(1, entry.totalSteps)
        assertEquals(FailureType.VERIFICATION_FAILED, entry.failureType)
        assertEquals(sessionId, entry.traceSessionId)
        assertNotNull(entry.completedAt)
    }

    @Test
    fun loadRecentHistorySortsByNewestAndAppliesLimit() {
        val store = FileTraceStore(temporaryFolder.root)

        val first = store.openSession("task-1", "第一个任务", "HYBRID")
        Thread.sleep(5)
        val second = store.openSession("task-2", "第二个任务", "HYBRID")
        Thread.sleep(5)
        val third = store.openSession("task-3", "第三个任务", "HYBRID")

        store.closeSession(first, TaskHistoryStatus.SUCCEEDED, "完成")
        store.closeSession(second, TaskHistoryStatus.SUCCEEDED, "完成")
        store.closeSession(third, TaskHistoryStatus.SUCCEEDED, "完成")

        val history = store.loadRecentHistory(limit = 2)
        assertEquals(2, history.size)
        assertEquals("第三个任务", history[0].taskGoal)
        assertEquals("第二个任务", history[1].taskGoal)
    }
}
