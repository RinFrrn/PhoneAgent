package com.mobileagent.phoneagent.harness.trace

import com.mobileagent.phoneagent.harness.act.ExecutionResult
import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.plan.PlanDecision
import com.mobileagent.phoneagent.harness.runtime.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskNoteExtractorTest {
    @Test
    fun extractsImportantContentNote() {
        val note = TaskNoteExtractor.fromActionJson(
            """{"_metadata":"do","action":"Note","content":"订单号 12345","category":"order","reason":"后续查询需要"}"""
        )

        assertEquals(TaskNoteType.IMPORTANT_CONTENT, note?.type)
        assertEquals("订单号 12345", note?.content)
        assertEquals("order", note?.category)
        assertTrue(note?.toDisplayText()?.contains("后续查询需要") == true)
    }

    @Test
    fun extractsTodoListNote() {
        val note = TaskNoteExtractor.fromActionJson(
            """{"_metadata":"do","action":"Note","todos":"- [x] 打开应用\n- [ ] 提交订单","reason":"更新进度"}"""
        )

        assertEquals(TaskNoteType.TODO_LIST, note?.type)
        assertTrue(note?.content?.contains("提交订单") == true)
    }

    @Test
    fun extractsReferenceRecordImportantContentAction() {
        val note = TaskNoteExtractor.fromActionJson(
            """{"action":"record_important_content","content":"余额 123 元","category":"finance","reason":"后续需要汇报"}"""
        )

        assertEquals(TaskNoteType.IMPORTANT_CONTENT, note?.type)
        assertEquals("余额 123 元", note?.content)
        assertEquals("finance", note?.category)
    }

    @Test
    fun extractsReferenceTodosAction() {
        val note = TaskNoteExtractor.fromActionJson(
            """{"action":"generate_or_update_todos","todos":"- [x] 搜索\n- [ ] 汇总","reason":"同步进度"}"""
        )

        assertEquals(TaskNoteType.TODO_LIST, note?.type)
        assertTrue(note?.content?.contains("汇总") == true)
    }

    @Test
    fun ignoresNonNoteActions() {
        assertNull(TaskNoteExtractor.fromActionJson("""{"_metadata":"do","action":"Tap","element":[1,2]}"""))
    }

    @Test
    fun summarizesNotesFromSessionSteps() {
        val session = sessionWithNote(
            TaskNote(
                type = TaskNoteType.IMPORTANT_CONTENT,
                content = "联系人: 张三",
                category = "contact",
                reason = "发送消息需要"
            )
        )

        val summary = TaskNoteSummaryBuilder.summarize(session)

        assertEquals(1, summary.notes.size)
        assertTrue(summary.toDisplayText().contains("联系人"))
    }

    @Test
    fun noteSummaryDisplayIncludesTaskNotes() {
        val session = sessionWithNote(
            TaskNote(
                type = TaskNoteType.PAGE_NOTE,
                content = "当前页面是订单详情"
            )
        )

        val text = TaskNoteSummaryBuilder.summarize(session).toDisplayText()

        assertTrue(text.contains("任务笔记: 1 条"))
        assertTrue(text.contains("当前页面是订单详情"))
    }

    private fun sessionWithNote(note: TaskNote): SessionTrace {
        return SessionTrace(
            sessionId = "s1",
            taskId = "task-1",
            taskGoal = "记录内容",
            mode = "HYBRID",
            startedAt = 1L,
            totalSteps = 1,
            steps = listOf(
                StepTrace(
                    stepIndex = 1,
                    timestamp = 1L,
                    status = StepStatus.EXECUTED,
                    observationBefore = Observation(
                        currentApp = "测试应用",
                        contentItems = emptyList()
                    ),
                    decision = PlanDecision(
                        thinking = "",
                        rawResponse = "",
                        actionJson = """{"_metadata":"do","action":"Note","content":"${note.content}"}"""
                    ),
                    execution = ExecutionResult(
                        success = true,
                        shouldFinish = false,
                        message = "已记录页面内容",
                        actionJson = """{"_metadata":"do","action":"Note","content":"${note.content}"}""",
                        taskNote = note
                    ),
                    observationAfter = null,
                    verification = null
                )
            )
        )
    }
}
