package com.mobileagent.phoneagent.harness.learn

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File

class LearnedSkillRepository(
    private val skillsFile: File
) {
    constructor(context: Context) : this(File(context.filesDir, LEARNED_SKILLS_FILE))

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun loadAll(): List<LearnedSkill> {
        if (!skillsFile.exists()) {
            return emptyList()
        }

        return runCatching {
            val type = object : TypeToken<List<LearnedSkill>>() {}.type
            gson.fromJson<List<LearnedSkill>>(skillsFile.readText(), type) ?: emptyList()
        }.onFailure { error ->
            Log.e(TAG, "读取动态技能失败", error)
        }.getOrDefault(emptyList())
    }

    fun loadPromptEnabled(): List<LearnedSkill> {
        return loadAll().filter { it.promptEnabled }
    }

    fun saveFromTrace(skill: LearnedSkill): Boolean {
        val existing = loadAll()
        if (existing.any { it.sourceTraceSessionId == skill.sourceTraceSessionId }) {
            return false
        }

        writeAll(existing + skill)
        return true
    }

    fun updateStatus(skillId: String, status: LearnedSkillStatus): Boolean {
        var changed = false
        val now = System.currentTimeMillis()
        val updated = loadAll().map { skill ->
            if (skill.id == skillId && skill.status != status) {
                changed = true
                skill.copy(status = status, updatedAt = now)
            } else {
                skill
            }
        }
        if (changed) {
            writeAll(updated)
        }
        return changed
    }

    fun delete(skillId: String): Boolean {
        val existing = loadAll()
        val updated = existing.filterNot { it.id == skillId }
        if (updated.size == existing.size) {
            return false
        }
        writeAll(updated)
        return true
    }

    private fun writeAll(skills: List<LearnedSkill>) {
        runCatching {
            skillsFile.parentFile?.mkdirs()
            skillsFile.writeText(gson.toJson(skills.sortedByDescending { it.createdAt }))
        }.onFailure { error ->
            Log.e(TAG, "写入动态技能失败", error)
        }
    }

    private companion object {
        const val TAG = "LearnedSkillRepo"
        const val LEARNED_SKILLS_FILE = "learned-skills.json"
    }
}
