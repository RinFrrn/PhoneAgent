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
    val verificationReason: String,
    val semanticAnchors: List<SemanticAnchor>? = null,
    val verificationSignals: List<VerificationSignal>? = null,
    val recoveryHints: List<String>? = null
)

enum class SemanticAnchorType {
    CURRENT_APP,
    CURRENT_PACKAGE,
    TARGET_TEXT,
    INPUT_TEXT,
    ACTION_MESSAGE,
    ACCESSIBILITY_EVENT,
    COORDINATE,
    SCREEN_TEXT
}

data class SemanticAnchor(
    val type: SemanticAnchorType,
    val value: String,
    val confidence: Float = 0.5f
)

enum class VerificationSignalType {
    EXPLICIT_FINISH,
    PACKAGE_REACHED,
    PACKAGE_CHANGED,
    APP_CHANGED,
    VISIBLE_TEXT_APPEARED,
    VISIBLE_TEXT_REMOVED,
    INPUT_TEXT_VISIBLE,
    CONTENT_CHANGED,
    GENERIC_VERIFICATION
}

data class VerificationSignal(
    val type: VerificationSignalType,
    val value: String,
    val description: String
)
