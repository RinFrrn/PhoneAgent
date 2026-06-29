package com.mobileagent.phoneagent.shortcut

data class TaskShortcut(
    val id: String,
    val title: String,
    val instruction: String,
    val category: String,
    val voiceKeywords: List<String> = emptyList(),
    val isSystem: Boolean = true
)

data class TaskShortcutUsage(
    val shortcutId: String,
    val useCount: Int = 0,
    val lastUsedAt: Long = 0L
)
