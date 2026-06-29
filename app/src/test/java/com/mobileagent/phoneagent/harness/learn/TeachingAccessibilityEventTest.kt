package com.mobileagent.phoneagent.harness.learn

import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.model.ContentItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachingAccessibilityEventTest {
    @Test
    fun recorderKeepsEventsAfterStartAndGoalUpdate() {
        TeachingRecorder.cancel()
        TeachingRecorder.start("初始目标")

        TeachingRecorder.recordAccessibilityEvent(
            TeachingAccessibilityEvent(
                eventType = "CLICK",
                packageName = "com.example.app",
                className = "android.widget.Button",
                text = "搜索",
                contentDescription = null,
                eventTime = 1L
            )
        )
        TeachingRecorder.updateGoal("修改后的目标")

        assertTrue(TeachingRecorder.isActive())
        assertEquals(1, TeachingRecorder.pendingEventCountForTest())
        TeachingRecorder.cancel()
    }

    @Test
    fun recorderIgnoresOwnOverlayEvents() {
        TeachingRecorder.cancel()
        TeachingRecorder.start("示教")

        TeachingRecorder.recordAccessibilityEvent(
            TeachingAccessibilityEvent(
                eventType = "CLICK",
                packageName = "com.mobileagent.phoneagent",
                className = "android.widget.Button",
                text = "记录",
                contentDescription = null,
                eventTime = 1L
            )
        )

        assertEquals(0, TeachingRecorder.pendingEventCountForTest())
        TeachingRecorder.cancel()
    }

    @Test
    fun recorderBuildsTaughtSkillWithAccessibilityEventEvidence() {
        TeachingRecorder.cancel()
        TeachingRecorder.start("搜索咖啡")

        TeachingRecorder.recordAccessibilityEvent(
            TeachingAccessibilityEvent(
                eventType = "CLICK",
                packageName = "com.example.app",
                className = "android.widget.Button",
                text = "搜索",
                contentDescription = null,
                eventTime = 1L
            )
        )
        TeachingRecorder.recordStepFromObservationForTest(
            goal = "搜索咖啡",
            note = "",
            observation = observation(texts = listOf("首页", "搜索")),
            recordedAt = 10L
        )

        TeachingRecorder.recordAccessibilityEvent(
            TeachingAccessibilityEvent(
                eventType = "TEXT_CHANGED",
                packageName = "com.example.app",
                className = "android.widget.EditText",
                text = "咖啡",
                contentDescription = null,
                eventTime = 2L
            )
        )
        TeachingRecorder.recordStepFromObservationForTest(
            goal = "搜索咖啡",
            note = "",
            observation = observation(texts = listOf("搜索", "咖啡", "取消")),
            recordedAt = 20L
        )

        val skill = TeachingRecorder.buildSkillSnapshotForTest(now = 100L)

        assertNotNull(skill)
        assertEquals("搜索咖啡", skill!!.sourceTaskGoal)
        assertEquals(2, skill.steps.size)
        assertEquals("人工示教：用户点击 搜索", skill.steps[0].actionSummary)
        assertTrue(
            skill.steps[0].semanticAnchors.orEmpty().any {
                it.type == SemanticAnchorType.ACCESSIBILITY_EVENT && it.value == "点击 搜索"
            }
        )
        assertTrue(
            skill.steps[1].verificationSignals.orEmpty().any {
                it.description == "示教操作事件" && it.value == "输入 咖啡"
            }
        )

        TeachingRecorder.cancel()
    }

    @Test
    fun clickEventBuildsTeachingSummary() {
        val event = TeachingAccessibilityEvent(
            eventType = "CLICK",
            packageName = "com.example.app",
            className = "android.widget.Button",
            text = "搜索",
            contentDescription = null,
            eventTime = 1L
        )

        assertTrue(event.isUsefulForTeaching())
        assertEquals("点击 搜索", event.summary())
    }

    @Test
    fun textChangedEventUsesContentText() {
        val event = TeachingAccessibilityEvent(
            eventType = "TEXT_CHANGED",
            packageName = "com.example.app",
            className = "android.widget.EditText",
            text = "咖啡",
            contentDescription = null,
            eventTime = 1L
        )

        assertTrue(event.isUsefulForTeaching())
        assertEquals("输入 咖啡", event.summary())
    }

    @Test
    fun autoNotePrefersConcreteUserActionOverNewerWindowChange() {
        val note = TeachingRecorder.buildAutoNoteForTest(
            listOf(
                TeachingAccessibilityEvent(
                    eventType = "CLICK",
                    packageName = "com.example.app",
                    className = "android.widget.Button",
                    text = "搜索",
                    contentDescription = null,
                    eventTime = 1L
                ),
                TeachingAccessibilityEvent(
                    eventType = "WINDOW_CHANGED",
                    packageName = "com.example.app",
                    className = "android.widget.FrameLayout",
                    text = "搜索结果页",
                    contentDescription = null,
                    eventTime = 2L
                )
            )
        )

        assertEquals("用户点击 搜索", note)
    }

    @Test
    fun autoNoteFallsBackToWindowChangeWhenNoActionExists() {
        val note = TeachingRecorder.buildAutoNoteForTest(
            listOf(
                TeachingAccessibilityEvent(
                    eventType = "WINDOW_CHANGED",
                    packageName = "com.example.app",
                    className = "android.widget.FrameLayout",
                    text = "详情页",
                    contentDescription = null,
                    eventTime = 1L
                )
            )
        )

        assertEquals("用户页面变化 详情页", note)
    }

    @Test
    fun eventWithoutLabelIsIgnored() {
        val event = TeachingAccessibilityEvent(
            eventType = "CLICK",
            packageName = "com.example.app",
            className = null,
            text = null,
            contentDescription = null,
            eventTime = 1L
        )

        assertFalse(event.isUsefulForTeaching())
        assertNull(event.summary())
    }

    private fun observation(texts: List<String>): Observation {
        return Observation(
            currentApp = "示例应用",
            currentPackage = "com.example.app",
            contentItems = texts.map { ContentItem(type = "text", text = it) }
        )
    }
}
