package com.mobileagent.phoneagent.promptcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PromptCardSelectorTest {
    @Test
    fun bundledPromptCardAssetParsesValidCards() {
        val json = listOf(
            File("src/main/assets/prompt_cards.json"),
            File("app/src/main/assets/prompt_cards.json")
        ).first { it.exists() }.readText()

        val cards = PromptCardRepository.parseCards(json)

        assertTrue(cards.size >= 6)
        assertTrue(cards.any { it.id == "safety" && it.alwaysOn })
        assertTrue(cards.any { it.id == "wechat" && "微信" in it.matchKeywords })
        assertTrue(cards.all { it.id.isNotBlank() && it.title.isNotBlank() && it.content.isNotBlank() })
    }

    @Test
    fun selectorIncludesAlwaysOnCardsForGenericTask() {
        val selected = PromptCardSelector.select(
            cards = sampleCards(),
            currentApp = "设置",
            task = "打开系统设置查看 Wi-Fi 状态",
            maxCards = 4
        )

        assertEquals(listOf("safety", "precision"), selected.map { it.id })
    }

    @Test
    fun selectorAddsMatchedAppAndInputCards() {
        val selected = PromptCardSelector.select(
            cards = sampleCards(),
            currentApp = "微信",
            task = "打开微信，给张三发送消息",
            maxCards = 4
        )

        assertTrue(selected.map { it.id }.containsAll(listOf("safety", "precision", "wechat", "text_input")))
    }

    @Test
    fun buildGuidanceFormatsSelectedCards() {
        val guidance = PromptCardSelector.buildGuidance(
            cards = sampleCards(),
            currentApp = "淘宝",
            task = "打开淘宝查看购物车",
            maxCards = 4
        )

        requireNotNull(guidance)
        assertTrue(guidance.contains("任务提示卡"))
        assertTrue(guidance.contains("购物助手"))
        assertTrue(guidance.contains("防误操作"))
    }

    private fun sampleCards(): List<PromptCard> {
        return listOf(
            PromptCard(
                id = "safety",
                title = "防误操作",
                description = "避免危险操作",
                content = "敏感操作请求用户接管。",
                category = "安全提示",
                alwaysOn = true,
                priority = 100
            ),
            PromptCard(
                id = "precision",
                title = "精确操作",
                description = "提高点击准确性",
                content = "点击前确认目标。",
                category = "操作优化",
                alwaysOn = true,
                priority = 90
            ),
            PromptCard(
                id = "wechat",
                title = "微信专用",
                description = "微信适配",
                content = "确认聊天对象。",
                category = "应用适配",
                matchKeywords = listOf("微信"),
                priority = 80
            ),
            PromptCard(
                id = "text_input",
                title = "文字输入优化",
                description = "输入适配",
                content = "确认输入框聚焦。",
                category = "输入优化",
                matchKeywords = listOf("发送", "输入", "搜索"),
                priority = 70
            ),
            PromptCard(
                id = "shopping",
                title = "购物助手",
                description = "购物适配",
                content = "确认规格和价格。",
                category = "应用适配",
                matchKeywords = listOf("淘宝", "购物车"),
                priority = 85
            )
        )
    }
}
