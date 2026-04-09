package com.mobileagent.phoneagent.skill

data class AppSkill(
    val id: String,
    val displayName: String,
    val appKeywords: List<String>,
    val launchAliases: List<String> = emptyList(),
    val guidance: String,
    val recoveryGuidance: Map<String, String> = emptyMap(),
    val fallbackProfile: String? = null
)
