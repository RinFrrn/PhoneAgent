package com.mobileagent.phoneagent.promptcard

data class PromptCard(
    val id: String,
    val title: String,
    val description: String,
    val content: String,
    val category: String,
    val matchKeywords: List<String> = emptyList(),
    val alwaysOn: Boolean = false,
    val priority: Int = 0
)
