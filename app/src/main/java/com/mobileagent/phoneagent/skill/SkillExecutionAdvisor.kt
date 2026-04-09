package com.mobileagent.phoneagent.skill

import android.content.Context
import com.mobileagent.phoneagent.action.ActionResult
import org.json.JSONObject

class SkillExecutionAdvisor {
    fun buildFailureRecoveryMessage(
        context: Context,
        currentApp: String?,
        task: String?,
        actionJson: String,
        actionResult: ActionResult
    ): String? {
        if (actionResult.success) {
            return null
        }

        val skill = SkillRegistry.matchingSkills(context, currentApp, task).firstOrNull() ?: return null
        val actionType = parseActionType(actionJson)
        val guidance = skill.recoveryGuidance[actionType] ?: return null

        return """
            ⚠️ 应用技能纠偏建议（${skill.displayName}）：
            上一步操作失败，失败信息：${actionResult.message ?: "未知原因"}
            建议优先尝试以下策略：
            $guidance
        """.trimIndent()
    }

    private fun parseActionType(actionJson: String): String {
        return try {
            val json = JSONObject(actionJson)
            when (json.optString("_metadata")) {
                "finish" -> "finish"
                "do" -> json.optString("action")
                else -> json.optString("_metadata")
            }
        } catch (_: Exception) {
            ""
        }
    }
}
