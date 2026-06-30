package com.mobileagent.phoneagent.harness.trace

import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.runtime.StepStatus
import com.mobileagent.phoneagent.model.ContentItem
import com.mobileagent.phoneagent.model.ImageUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualContextSummaryBuilderTest {
    @Test
    fun emptySessionReportsNoVisualContext() {
        val summary = VisualContextSummaryBuilder.summarize(session(emptyList()))

        assertTrue(summary.isEmpty())
        assertEquals("视觉上下文: 未记录", summary.toDisplayText())
    }

    @Test
    fun summarizesImagesAndTextAcrossObservations() {
        val session = session(
            listOf(
                step(
                    index = 1,
                    before = observation(
                        text = "屏幕文本",
                        imageChars = 100
                    ),
                    after = observation(imageChars = 120)
                ),
                step(
                    index = 2,
                    before = observation(text = "更多文本"),
                    after = null
                )
            )
        )

        val summary = VisualContextSummaryBuilder.summarize(session)

        assertEquals(3, summary.observationCount)
        assertEquals(2, summary.imageObservationCount)
        assertEquals(1, summary.stepsWithImages)
        assertEquals(220, summary.totalImageChars)
        assertEquals(120, summary.maxImageChars)
        assertEquals(8, summary.textChars)
        assertTrue(summary.toDisplayText().contains("图片 2 次/1 步"))
    }

    @Test
    fun reportsCaptureFailuresAndLargeImageWarnings() {
        val session = session(
            listOf(
                step(
                    index = 1,
                    before = observation(
                        imageChars = 2_000_000,
                        failureMessage = "截图失败"
                    )
                )
            )
        )

        val summary = VisualContextSummaryBuilder.summarize(session)

        assertEquals(1, summary.captureFailureCount)
        assertTrue(summary.warnings().any { it.contains("截图失败") })
        assertTrue(summary.warnings().any { it.contains("单次截图上下文偏大") })
    }

    @Test
    fun pureTextObservationIsNotFlaggedAsImageInput() {
        val summary = VisualContextSummaryBuilder.summarize(
            session(
                listOf(step(index = 1, before = observation(text = "纯文本")))
            )
        )

        assertFalse(summary.hasImages())
        assertTrue(summary.toDisplayText().contains("无图片输入"))
    }

    private fun session(steps: List<StepTrace>): SessionTrace {
        return SessionTrace(
            sessionId = "s1",
            taskId = "t1",
            taskGoal = "视觉任务",
            mode = "HYBRID",
            startedAt = 1L,
            totalSteps = steps.size,
            steps = steps
        )
    }

    private fun step(
        index: Int,
        before: Observation,
        after: Observation? = null
    ): StepTrace {
        return StepTrace(
            stepIndex = index,
            timestamp = index.toLong(),
            status = StepStatus.EXECUTED,
            observationBefore = before,
            decision = null,
            execution = null,
            observationAfter = after,
            verification = null
        )
    }

    private fun observation(
        text: String? = null,
        imageChars: Int? = null,
        failureMessage: String? = null
    ): Observation {
        val items = buildList {
            text?.let { add(ContentItem(type = "text", text = it)) }
            imageChars?.let { add(ContentItem(type = "image_url", imageUrl = ImageUrl(url = "x".repeat(it)))) }
        }
        return Observation(
            currentApp = "测试应用",
            contentItems = items,
            failureMessage = failureMessage
        )
    }
}
