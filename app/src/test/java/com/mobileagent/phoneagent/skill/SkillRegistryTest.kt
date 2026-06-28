package com.mobileagent.phoneagent.skill

import com.mobileagent.phoneagent.harness.learn.LearnedSkill
import com.mobileagent.phoneagent.harness.learn.LearnedSkillStep
import com.mobileagent.phoneagent.harness.learn.LearnedSkillStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRegistryTest {
    @Test
    fun staticAndDynamicSkillsCanBothMatch() {
        val staticSkill = AppSkill(
            id = "wechat",
            displayName = "微信",
            appKeywords = listOf("微信"),
            guidance = "静态微信技能",
            fallbackProfile = "wechat"
        )
        val dynamicSkill = SkillRegistry.run { learnedSkill(status = LearnedSkillStatus.DRAFT).toAppSkill() }

        val matches = SkillRegistry.matchingSkills(
            listOf(staticSkill, dynamicSkill),
            currentApp = "微信",
            task = "打开微信并搜索联系人"
        )

        assertEquals(listOf("wechat", "learned-1"), matches.map { it.id })
    }

    @Test
    fun disabledDynamicSkillIsNotPromptEnabled() {
        val enabled = listOf(
            learnedSkill("enabled", LearnedSkillStatus.DRAFT),
            learnedSkill("disabled", LearnedSkillStatus.DISABLED)
        ).filter { it.promptEnabled }

        assertEquals(listOf("enabled"), enabled.map { it.id })
    }

    @Test
    fun dynamicSkillDoesNotProvideFallbackProfile() {
        val appSkill = SkillRegistry.run { learnedSkill(status = LearnedSkillStatus.DRAFT).toAppSkill() }

        assertNull(appSkill.fallbackProfile)
        assertTrue(appSkill.recoveryGuidance.isEmpty())
    }

    private fun learnedSkill(
        id: String = "learned-1",
        status: LearnedSkillStatus
    ): LearnedSkill {
        return LearnedSkill(
            id = id,
            displayName = "路径：微信搜索",
            sourceTraceSessionId = "trace-1",
            sourceTaskGoal = "打开微信并搜索联系人",
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
                    actionSummary = "点击搜索",
                    successSignal = "页面变化",
                    verificationReason = "验证通过"
                )
            ),
            status = status,
            createdAt = 1L,
            updatedAt = 1L
        )
    }
}
