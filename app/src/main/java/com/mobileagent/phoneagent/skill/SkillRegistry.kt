package com.mobileagent.phoneagent.skill

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object SkillRegistry {
    private const val TAG = "SkillRegistry"
    private const val SKILL_ASSET = "app_skills.json"

    @Volatile
    private var cachedSkills: List<AppSkill>? = null

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
        val appText = currentApp.orEmpty().lowercase()
        val taskText = task.orEmpty().lowercase()
        return loadSkills(context).filter { skill ->
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
        cachedSkills?.let { return it }

        synchronized(this) {
            cachedSkills?.let { return it }
            val loadedSkills = try {
                val json = context.assets.open(SKILL_ASSET).bufferedReader().use { it.readText() }
                parseSkills(JSONArray(json))
            } catch (e: Exception) {
                Log.e(TAG, "加载技能配置失败，使用空配置", e)
                emptyList()
            }
            cachedSkills = loadedSkills
            return loadedSkills
        }
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
