package com.mobileagent.phoneagent.harness.trace

import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.act.ExecutionResult
import com.mobileagent.phoneagent.harness.plan.PlanDecision
import com.mobileagent.phoneagent.harness.recover.FailureType
import com.mobileagent.phoneagent.harness.runtime.StepStatus
import com.mobileagent.phoneagent.harness.verify.VerificationResult
import com.mobileagent.phoneagent.model.ContentItem
import com.mobileagent.phoneagent.model.ImageUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

        val runningTrace = store.loadSession(sessionId)
        requireNotNull(runningTrace)
        assertEquals(TaskHistoryStatus.RUNNING, runningTrace.status)
        assertEquals(TraceSanitizer.DATA_POLICY, runningTrace.dataPolicy)
        assertEquals(0, runningTrace.totalSteps)
        assertNull(runningTrace.completedAt)
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
        assertEquals(FailureType.VERIFICATION_FAILED, store.loadSession(sessionId)?.failureType)
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

    @Test
    fun appendedStepIsDurableBeforeSessionCloses() {
        val writer = FileTraceStore(temporaryFolder.root)
        val sessionId = writer.openSession("task-running", "打开设置", "ACCESSIBILITY")
        writer.appendStep(sessionId, basicStep())

        val restartedReader = FileTraceStore(temporaryFolder.root)
        val runningTrace = restartedReader.loadSession(sessionId)

        requireNotNull(runningTrace)
        assertEquals(TaskHistoryStatus.RUNNING, runningTrace.status)
        assertEquals(1, runningTrace.totalSteps)
        assertEquals(1, runningTrace.steps.single().stepIndex)
        assertTrue(
            temporaryFolder.root.walkTopDown().none { file -> file.isFile && file.extension == "tmp" }
        )
    }

    @Test
    fun resumedSessionPersistsParentTraceLineage() {
        val store = FileTraceStore(temporaryFolder.root)
        val sessionId = store.openSession(
            taskId = "task-resumed",
            taskGoal = "继续打开设置",
            mode = "ACCESSIBILITY",
            resumedFromSessionId = "source-session",
            resumeStrategy = TraceResumeStrategy.FRESH_OBSERVATION,
            resumedPriorStepCount = 4
        )

        assertEquals("source-session", store.loadSession(sessionId)?.resumedFromSessionId)
        assertEquals("source-session", store.loadRecentHistory().single().resumedFromSessionId)
        assertEquals(TraceResumeStrategy.FRESH_OBSERVATION, store.loadSession(sessionId)?.resumeStrategy)
        assertEquals(4, store.loadSession(sessionId)?.resumedPriorStepCount)
    }

    @Test
    fun persistedTraceRemovesImagePayloadAndSensitiveActionContent() {
        val store = FileTraceStore(temporaryFolder.root)
        val secretText = "payment-password-789"
        val sessionId = store.openSession(
            taskId = "task-private",
            taskGoal = "输入 $secretText，把验证码 123456 发给 13800138000",
            mode = "HYBRID"
        )
        assertFalse(store.loadRecentHistory().single().taskGoal.contains(secretText))
        val imagePayload = "data:image/png;base64," + "A".repeat(1024)
        store.appendStep(
            sessionId,
            StepTrace(
                stepIndex = 1,
                timestamp = 123L,
                status = StepStatus.EXECUTED,
                observationBefore = Observation(
                    currentApp = "短信",
                    contentItems = listOf(
                        ContentItem(type = "text", text = "验证码 123456，联系人 13800138000"),
                        ContentItem(type = "image_url", imageUrl = ImageUrl(imagePayload))
                    )
                ),
                decision = PlanDecision(
                    thinking = "读取验证码 123456 后输入 $secretText",
                    rawResponse = """{"action":"Type","text":"$secretText"}""",
                    actionJson = """{"action":"Type","text":"$secretText"}"""
                ),
                execution = ExecutionResult(
                    success = true,
                    shouldFinish = false,
                    message = "文本输入成功: $secretText",
                    actionJson = """{"action":"Type","text":"$secretText"}"""
                ),
                observationAfter = Observation(
                    currentApp = "支付页",
                    contentItems = listOf(
                        ContentItem(type = "text", text = "输入框显示 $secretText")
                    )
                ),
                verification = VerificationResult(
                    passed = true,
                    confidence = 1f,
                    reason = "已看到 $secretText",
                    observedChange = "输入框从空变为 $secretText"
                ),
                errorMessage = "调试回显 $secretText"
            )
        )

        val persisted = requireNotNull(FileTraceStore(temporaryFolder.root).loadSession(sessionId))
        val persistedJson = temporaryFolder.root.walkTopDown()
            .first { it.isFile && it.name == "session-$sessionId.json" }
            .readText()

        assertFalse(persistedJson.contains(imagePayload))
        assertFalse(persistedJson.contains(secretText))
        assertFalse(persistedJson.contains("123456"))
        assertFalse(persistedJson.contains("13800138000"))
        assertTrue(persisted.steps.single().decision?.actionJson.orEmpty().contains("[REDACTED]"))
        assertEquals(
            imagePayload.length,
            VisualContextSummaryBuilder.summarize(persisted).totalImageChars
        )
        assertFalse(store.loadRecentHistory().single().taskGoal.contains(secretText))
    }

    private fun basicStep(): StepTrace {
        return StepTrace(
            stepIndex = 1,
            timestamp = 123L,
            status = StepStatus.EXECUTED,
            observationBefore = Observation(currentApp = "设置", contentItems = emptyList()),
            decision = null,
            execution = null,
            observationAfter = null,
            verification = null
        )
    }
}
