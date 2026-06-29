package com.mobileagent.phoneagent.skill

import com.mobileagent.phoneagent.harness.learn.LearnedSkill
import com.mobileagent.phoneagent.harness.learn.LearnedSkillStep
import com.mobileagent.phoneagent.harness.learn.LearnedSkillStatus
import com.mobileagent.phoneagent.harness.learn.SemanticAnchor
import com.mobileagent.phoneagent.harness.learn.SemanticAnchorType
import com.mobileagent.phoneagent.harness.learn.VerificationSignal
import com.mobileagent.phoneagent.harness.learn.VerificationSignalType
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

    @Test
    fun dynamicSkillGuidanceIncludesSemanticReplayEvidence() {
        val learnedSkill = learnedSkill(status = LearnedSkillStatus.DRAFT).copy(
            steps = listOf(
                LearnedSkillStep(
                    stepIndex = 1,
                    actionType = "Tap",
                    targetHint = "坐标(120, 240)",
                    actionSummary = "点击搜索框",
                    successSignal = "出现取消按钮",
                    verificationReason = "页面内容变化",
                    semanticAnchors = listOf(
                        SemanticAnchor(SemanticAnchorType.ACTION_MESSAGE, "点击搜索框", 0.7f),
                        SemanticAnchor(SemanticAnchorType.SCREEN_TEXT, "搜索", 0.5f)
                    ),
                    verificationSignals = listOf(
                        VerificationSignal(
                            VerificationSignalType.VISIBLE_TEXT_APPEARED,
                            "取消",
                            "执行后出现新文本"
                        )
                    ),
                    recoveryHints = listOf("目标缺失时先等待加载，不要重复点击旧坐标。")
                )
            )
        )

        val appSkill = SkillRegistry.run { learnedSkill.toAppSkill() }

        assertTrue(appSkill.guidance.contains("语义锚点"))
        assertTrue(appSkill.guidance.contains("ACTION_MESSAGE:点击搜索框"))
        assertTrue(appSkill.guidance.contains("验证信号"))
        assertTrue(appSkill.guidance.contains("VISIBLE_TEXT_APPEARED:取消"))
        assertTrue(appSkill.guidance.contains("不是固定回放脚本"))
        assertEquals(
            "目标缺失时先等待加载，不要重复点击旧坐标。",
            appSkill.recoveryGuidance["Tap"]
        )
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
