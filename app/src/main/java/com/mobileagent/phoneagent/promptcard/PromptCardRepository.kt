package com.mobileagent.phoneagent.promptcard

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class PromptCardRepository(
    private val context: Context,
    private val gson: Gson = Gson()
) {
    fun loadCards(): List<PromptCard> {
        return runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().use { reader ->
                parseCards(reader.readText())
            }
        }.getOrDefault(fallbackCards())
    }

    fun buildGuidance(currentApp: String?, task: String?, maxCards: Int = DEFAULT_MAX_CARDS): String? {
        return PromptCardSelector.buildGuidance(
            cards = loadCards(),
            currentApp = currentApp,
            task = task,
            maxCards = maxCards
        )
    }

    companion object {
        private const val ASSET_NAME = "prompt_cards.json"
        private const val DEFAULT_MAX_CARDS = 4
        private val gson = Gson()

        fun parseCards(json: String): List<PromptCard> {
            val type = object : TypeToken<List<PromptCard>>() {}.type
            return gson.fromJson<List<PromptCard>>(json, type)
                .orEmpty()
                .filter { card ->
                    card.id.isNotBlank() &&
                        card.title.isNotBlank() &&
                        card.content.isNotBlank()
                }
        }

        fun fallbackCards(): List<PromptCard> {
            return listOf(
                PromptCard(
                    id = "fallback_safety",
                    title = "防误操作",
                    description = "避免危险操作",
                    content = "遇到支付、删除、授权等敏感操作时，请请求用户接管。",
                    category = "安全提示",
                    alwaysOn = true,
                    priority = 100
                ),
                PromptCard(
                    id = "fallback_precision",
                    title = "精确操作",
                    description = "提高点击准确性",
                    content = "点击前确认目标控件和坐标，避免重复点击同一无效位置。",
                    category = "操作优化",
                    alwaysOn = true,
                    priority = 90
                )
            )
        }
    }
}
