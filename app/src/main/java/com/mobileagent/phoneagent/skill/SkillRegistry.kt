package com.mobileagent.phoneagent.skill

import android.content.Context
import android.util.Log
import com.mobileagent.phoneagent.harness.learn.LearnedSkill
import com.mobileagent.phoneagent.harness.learn.LearnedSkillRepository
import org.json.JSONArray
import org.json.JSONObject

object SkillRegistry {
    private const val TAG = "SkillRegistry"
    private const val SKILL_ASSET = "app_skills.json"

    @Volatile
    private var cachedAssetSkills: List<AppSkill>? = null

    fun buildSkillGuidance(context: Context, currentApp: String?, task: String?): String? {
        val matches = matchingSkills(context, currentApp, task)
        if (matches.isEmpty()) return null

        return buildString {
            append("以下是当前任务匹配到的应用技能，请优先遵循：\n")
            matches.forEach { skill ->
                append("- ${skill.displayName} 技能\n")
                append(skill.guidance)
                append("\n")
            }
        }.trim()
    }

    fun matchingSkills(context: Context, currentApp: String?, task: String?): List<AppSkill> {
        return matchingSkills(loadSkills(context), currentApp, task)
    }

    internal fun matchingSkills(skills: List<AppSkill>, currentApp: String?, task: String?): List<AppSkill> {
        val appText = currentApp.orEmpty().lowercase()
        val taskText = task.orEmpty().lowercase()
        return skills.filter { skill ->
            skill.appKeywords.any { keyword ->
                val normalized = keyword.lowercase()
                appText.contains(normalized) || taskText.contains(normalized)
            }
        }
    }

    fun expandLaunchCandidates(context: Context, appName: String): List<String> {
        val normalized = appName.trim().lowercase()
        val matched = loadSkills(context).filter { skill ->
            skill.appKeywords.any { normalized.contains(it.lowercase()) } ||
                skill.launchAliases.any { normalized.contains(it.lowercase()) }
        }
        return (listOf(appName) + matched.flatMap { it.launchAliases }).distinct()
    }

    fun loadSkills(context: Context): List<AppSkill> {
        return loadAssetSkills(context) + loadLearnedSkills(context)
    }

    fun invalidateCache() {
        cachedAssetSkills = null
    }

    private fun loadAssetSkills(context: Context): List<AppSkill> {
        cachedAssetSkills?.let { return it }
        synchronized(this) {
            cachedAssetSkills?.let { return it }
            val loadedSkills = try {
                val json = context.assets.open(SKILL_ASSET).bufferedReader().use { it.readText() }
                parseSkills(JSONArray(json))
            } catch (e: Exception) {
                Log.e(TAG, "加载技能配置失败，使用空配置", e)
                emptyList()
            }
            cachedAssetSkills = loadedSkills
            return loadedSkills
        }
    }

    private fun loadLearnedSkills(context: Context): List<AppSkill> {
        return LearnedSkillRepository(context)
            .loadPromptEnabled()
            .map { it.toAppSkill() }
    }

    internal fun LearnedSkill.toAppSkill(): AppSkill {
        return AppSkill(
            id = id,
            displayName = displayName,
            appKeywords = (appKeywords + packageNames + sourceTaskGoal)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct(),
            launchAliases = emptyList(),
            guidance = buildLearnedGuidance(),
            recoveryGuidance = buildLearnedRecoveryGuidance(),
            fallbackProfile = null
        )
    }

    private fun LearnedSkill.buildLearnedGuidance(): String {
        return buildString {
            append("动态路径技能（草稿，来源 trace ${sourceTraceSessionId.take(8)}）：\n")
            append("任务目标：$sourceTaskGoal\n")
            append("路径摘要：$summary\n")
            caution?.let {
                append("注意：$it\n")
            }
            append("可复用步骤：\n")
            steps.forEachIndexed { index, step ->
                append("${index + 1}. ${step.actionSummary}")
                val anchors = step.semanticAnchors.orEmpty()
                    .take(4)
                    .joinToString("，") { "${it.type}:${it.value}" }
                if (anchors.isNotBlank()) {
                    append("；语义锚点：$anchors")
                }
                append("；成功信号：${step.successSignal}")
                val signals = step.verificationSignals.orEmpty()
                    .take(3)
                    .joinToString("，") { "${it.type}:${it.value}" }
                if (signals.isNotBlank()) {
                    append("；验证信号：$signals")
                }
                val hints = step.recoveryHints.orEmpty().joinToString("；")
                if (hints.isNotBlank()) {
                    append("；异常处理：$hints")
                }
                append("；验证：${step.verificationReason}\n")
            }
            append("复用要求：把这些步骤当作已验证路径和页面证据，而不是固定回放脚本。")
            append("优先按语义锚点重新定位目标；每一步后检查验证信号。")
            append("页面不同、加载未完成、出现广告/弹窗或涉及敏感操作时，先恢复当前页面或重新规划，不要盲目照搬坐标。")
        }.trim()
    }

    private fun LearnedSkill.buildLearnedRecoveryGuidance(): Map<String, String> {
        return steps
            .groupBy { it.actionType }
            .mapValues { (_, steps) ->
                steps
                    .flatMap { it.recoveryHints.orEmpty() }
                    .distinct()
                    .joinToString("\n")
            }
            .filterValues { it.isNotBlank() }
    }

    private fun parseSkills(array: JSONArray): List<AppSkill> {
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(
                    AppSkill(
                        id = item.optString("id"),
                        displayName = item.optString("displayName"),
                        appKeywords = item.optJSONArray("appKeywords").toStringList(),
                        launchAliases = item.optJSONArray("launchAliases").toStringList(),
                        guidance = item.optString("guidance"),
                        recoveryGuidance = item.optJSONObject("recoveryGuidance").toStringMap(),
                        fallbackProfile = item.optString("fallbackProfile").ifBlank { null }
                    )
                )
            }
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                val value = optString(i)
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return keys().asSequence().associateWith { key -> optString(key) }
    }
}
