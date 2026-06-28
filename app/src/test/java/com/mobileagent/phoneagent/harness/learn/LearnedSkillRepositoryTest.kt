package com.mobileagent.phoneagent.harness.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LearnedSkillRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun savesLoadsUpdatesAndDeletesSkill() {
        val repository = LearnedSkillRepository(tempFolder.newFile("learned-skills.json"))
        val skill = learnedSkill(id = "skill-1", sourceTraceSessionId = "trace-1")

        assertTrue(repository.saveFromTrace(skill))
        assertEquals(listOf(skill), repository.loadAll())

        assertTrue(repository.updateStatus("skill-1", LearnedSkillStatus.DISABLED))
        assertFalse(repository.loadPromptEnabled().any { it.id == "skill-1" })
        assertEquals(LearnedSkillStatus.DISABLED, repository.loadAll().first().status)

        assertTrue(repository.delete("skill-1"))
        assertTrue(repository.loadAll().isEmpty())
    }

    @Test
    fun duplicateSourceTraceIsNotSavedTwice() {
        val repository = LearnedSkillRepository(tempFolder.newFile("learned-skills.json"))

        assertTrue(repository.saveFromTrace(learnedSkill(id = "skill-1", sourceTraceSessionId = "trace-1")))
        assertFalse(repository.saveFromTrace(learnedSkill(id = "skill-2", sourceTraceSessionId = "trace-1")))

        assertEquals(1, repository.loadAll().size)
        assertEquals("skill-1", repository.loadAll().first().id)
    }

    private fun learnedSkill(id: String, sourceTraceSessionId: String): LearnedSkill {
        return LearnedSkill(
            id = id,
            displayName = "路径：测试",
            sourceTraceSessionId = sourceTraceSessionId,
            sourceTaskGoal = "打开微信",
            appKeywords = listOf("微信"),
            packageNames = listOf("com.tencent.mm"),
            summary = "测试路径",
            steps = listOf(
                LearnedSkillStep(
                    stepIndex = 1,
                    actionType = "Launch",
                    targetHint = "微信",
                    actionSummary = "启动微信",
                    successSignal = "打开成功",
                    verificationReason = "包名匹配"
                ),
                LearnedSkillStep(
                    stepIndex = 2,
                    actionType = "Tap",
                    targetHint = "坐标(1, 2)",
                    actionSummary = "点击坐标",
                    successSignal = "页面变化",
                    verificationReason = "验证通过"
                )
            ),
            createdAt = 1L,
            updatedAt = 1L
        )
    }
}
