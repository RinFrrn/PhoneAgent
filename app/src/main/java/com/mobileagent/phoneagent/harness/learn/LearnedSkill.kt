package com.mobileagent.phoneagent.harness.learn

enum class LearnedSkillStatus {
    DRAFT,
    DISABLED
}

data class LearnedSkill(
    val id: String,
    val displayName: String,
    val sourceTraceSessionId: String,
    val sourceTaskGoal: String,
    val appKeywords: List<String>,
    val packageNames: List<String>,
    val summary: String,
    val caution: String? = null,
    val steps: List<LearnedSkillStep>,
    val status: LearnedSkillStatus = LearnedSkillStatus.DRAFT,
    val createdAt: Long,
    val updatedAt: Long
) {
    val promptEnabled: Boolean
        get() = status == LearnedSkillStatus.DRAFT
}

data class LearnedSkillStep(
    val stepIndex: Int,
    val actionType: String,
    val targetHint: String,
    val actionSummary: String,
    val successSignal: String,
    val verificationReason: String
)
