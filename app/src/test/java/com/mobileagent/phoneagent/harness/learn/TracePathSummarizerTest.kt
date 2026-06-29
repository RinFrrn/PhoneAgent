package com.mobileagent.phoneagent.harness.learn

import com.mobileagent.phoneagent.harness.act.ExecutionResult
import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.runtime.StepStatus
import com.mobileagent.phoneagent.harness.trace.SessionTrace
import com.mobileagent.phoneagent.harness.trace.StepTrace
import com.mobileagent.phoneagent.harness.verify.VerificationResult
import com.mobileagent.phoneagent.model.ContentItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TracePathSummarizerTest {
    private val summarizer = TracePathSummarizer()

    @Test
    fun successfulTraceCreatesLearnedSkill() {
        val session = sessionTrace(
            success = true,
            steps = listOf(
                reusableStep(1, """{"_metadata":"do","action":"Launch","app":"微信"}"""),
                reusableStep(2, """{"_metadata":"do","action":"Tap","element":[120,240]}""")
            )
        )

        val skill = summarizer.summarize(session, now = 1000L)

        assertNotNull(skill)
        assertEquals("trace-1", skill!!.sourceTraceSessionId)
        assertEquals(LearnedSkillStatus.DRAFT, skill.status)
        assertEquals(2, skill.steps.size)
        assertEquals("Launch", skill.steps[0].actionType)
        assertEquals("Tap", skill.steps[1].actionType)
    }

    @Test
    fun failedTraceDoesNotCreateLearnedSkill() {
        val session = sessionTrace(
            success = false,
            steps = listOf(
                reusableStep(1, """{"_metadata":"do","action":"Launch","app":"微信"}"""),
                reusableStep(2, """{"_metadata":"do","action":"Tap","element":[120,240]}""")
            )
        )

        assertNull(summarizer.summarize(session))
    }

    @Test
    fun successfulTraceWithTooFewReusableStepsDoesNotCreateLearnedSkill() {
        val session = sessionTrace(
            success = true,
            steps = listOf(
                reusableStep(1, """{"_metadata":"do","action":"Launch","app":"微信"}""")
            )
        )

        assertNull(summarizer.summarize(session))
    }

    @Test
    fun failedMiddleStepIsExcludedAndRecordedAsCaution() {
        val session = sessionTrace(
            success = true,
            steps = listOf(
                reusableStep(1, """{"_metadata":"do","action":"Launch","app":"微信"}"""),
                failedStep(2),
                reusableStep(3, """{"_metadata":"do","action":"Tap","element":[120,240]}""")
            )
        )

        val skill = summarizer.summarize(session)

        assertNotNull(skill)
        assertEquals(listOf(1, 3), skill!!.steps.map { it.stepIndex })
        assertNotNull(skill.caution)
    }

    @Test
    fun successfulTraceStoresSemanticAnchorsAndVerificationSignals() {
        val session = sessionTrace(
            success = true,
            steps = listOf(
                reusableStep(
                    stepIndex = 1,
                    actionJson = """{"_metadata":"do","action":"Tap","element":[120,240],"message":"点击搜索框"}""",
                    before = observation(texts = listOf("首页", "搜索")),
                    after = observation(texts = listOf("搜索", "取消"))
                ),
                reusableStep(
                    stepIndex = 2,
                    actionJson = """{"_metadata":"do","action":"Type","text":"咖啡"}""",
                    before = observation(texts = listOf("搜索", "取消")),
                    after = observation(texts = listOf("搜索", "咖啡", "取消"))
                )
            )
        )

        val skill = summarizer.summarize(session)

        assertNotNull(skill)
        val tapStep = skill!!.steps[0]
        assertTrue(tapStep.semanticAnchors.orEmpty().any { it.type == SemanticAnchorType.ACTION_MESSAGE && it.value == "点击搜索框" })
        assertTrue(tapStep.semanticAnchors.orEmpty().any { it.type == SemanticAnchorType.SCREEN_TEXT && it.value == "搜索" })
        assertTrue(tapStep.verificationSignals.orEmpty().any { it.type == VerificationSignalType.VISIBLE_TEXT_APPEARED && it.value == "取消" })

        val typeStep = skill.steps[1]
        assertTrue(typeStep.semanticAnchors.orEmpty().any { it.type == SemanticAnchorType.INPUT_TEXT && it.value == "咖啡" })
        assertTrue(typeStep.verificationSignals.orEmpty().any { it.type == VerificationSignalType.INPUT_TEXT_VISIBLE && it.value == "咖啡" })
        assertTrue(typeStep.recoveryHints.orEmpty().any { it.contains("输入后检查输入内容") })
    }

    private fun sessionTrace(
        success: Boolean?,
        steps: List<StepTrace>
    ): SessionTrace {
        return SessionTrace(
            sessionId = "trace-1",
            taskId = "task-1",
            taskGoal = "打开微信并搜索联系人",
            mode = "HYBRID",
            startedAt = 1L,
            completedAt = 2L,
            success = success,
            totalSteps = steps.size,
            steps = steps
        )
    }

    private fun reusableStep(
        stepIndex: Int,
        actionJson: String,
        before: Observation = observation(),
        after: Observation = observation()
    ): StepTrace {
        return StepTrace(
            stepIndex = stepIndex,
            timestamp = stepIndex.toLong(),
            status = if (stepIndex == 3) StepStatus.FINISHED else StepStatus.EXECUTED,
            observationBefore = before,
            decision = null,
            execution = ExecutionResult(
                success = true,
                shouldFinish = stepIndex == 3,
                message = "ok",
                actionJson = actionJson
            ),
            observationAfter = after,
            verification = VerificationResult(
                passed = true,
                confidence = 0.9f,
                reason = "页面发生有效变化",
                observedChange = "页面变化"
            )
        )
    }

    private fun failedStep(stepIndex: Int): StepTrace {
        return StepTrace(
            stepIndex = stepIndex,
            timestamp = stepIndex.toLong(),
            status = StepStatus.FAILED,
            observationBefore = observation(),
            decision = null,
            execution = ExecutionResult(
                success = false,
                shouldFinish = false,
                message = "tap failed",
                actionJson = """{"_metadata":"do","action":"Tap","element":[1,1]}"""
            ),
            observationAfter = observation(),
            verification = VerificationResult(
                passed = false,
                confidence = 0.2f,
                reason = "未生效"
            )
        )
    }

    private fun observation(texts: List<String> = emptyList()): Observation {
        return Observation(
            currentApp = "微信",
            currentPackage = "com.tencent.mm",
            contentItems = texts.map { ContentItem(type = "text", text = it) }
        )
    }
}
