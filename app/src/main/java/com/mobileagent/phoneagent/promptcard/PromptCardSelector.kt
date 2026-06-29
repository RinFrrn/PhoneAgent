package com.mobileagent.phoneagent.promptcard

object PromptCardSelector {
    fun select(
        cards: List<PromptCard>,
        currentApp: String?,
        task: String?,
        maxCards: Int = 4
    ): List<PromptCard> {
        if (cards.isEmpty() || maxCards <= 0) {
            return emptyList()
        }
        val contextText = listOfNotNull(currentApp, task)
            .joinToString(separator = "\n")
            .lowercase()

        return cards
            .mapNotNull { card ->
                val score = score(card, contextText)
                if (score > 0) card to score else null
            }
            .sortedWith(
                compareByDescending<Pair<PromptCard, Int>> { it.second }
                    .thenByDescending { it.first.priority }
                    .thenBy { it.first.title }
            )
            .map { it.first }
            .distinctBy { it.id }
            .take(maxCards)
    }

    fun buildGuidance(
        cards: List<PromptCard>,
        currentApp: String?,
        task: String?,
        maxCards: Int = 4
    ): String? {
        val selected = select(cards, currentApp, task, maxCards)
        if (selected.isEmpty()) {
            return null
        }

        return buildString {
            appendLine("**任务提示卡（根据当前任务动态注入）**")
            selected.forEachIndexed { index, card ->
                appendLine("${index + 1}. ${card.title} / ${card.category}: ${card.content}")
            }
            append("这些提示用于提升稳定性和安全性；如果与用户目标冲突，以用户明确目标和安全接管规则为准。")
        }
    }

    private fun score(card: PromptCard, contextText: String): Int {
        var score = if (card.alwaysOn) 1_000 else 0
        if (contextText.isBlank()) {
            return score + card.priority
        }

        val keywordHits = card.matchKeywords.count { keyword ->
            keyword.isNotBlank() && contextText.contains(keyword.lowercase())
        }
        if (keywordHits == 0 && !card.alwaysOn) {
            return 0
        }
        score += keywordHits * 100
        score += card.priority
        return score
    }
}
