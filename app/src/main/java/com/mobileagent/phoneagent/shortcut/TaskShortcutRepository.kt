package com.mobileagent.phoneagent.shortcut

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class TaskShortcutRepository(
    private val context: Context,
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE),
    private val gson: Gson = Gson()
) {
    fun loadShortcuts(): List<TaskShortcut> {
        return runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().use { reader ->
                parseShortcuts(reader.readText())
            }
        }.getOrDefault(fallbackShortcuts())
    }

    fun rankedShortcuts(shortcuts: List<TaskShortcut> = loadShortcuts()): List<TaskShortcut> {
        val usage = readUsage()
        return shortcuts.sortedWith(
            compareByDescending<TaskShortcut> { usage[it.id]?.useCount ?: 0 }
                .thenByDescending { usage[it.id]?.lastUsedAt ?: 0L }
                .thenBy { it.category }
                .thenBy { it.title }
        )
    }

    fun recordUsed(shortcutId: String, now: Long = System.currentTimeMillis()) {
        val usage = readUsage().toMutableMap()
        val current = usage[shortcutId]
        usage[shortcutId] = TaskShortcutUsage(
            shortcutId = shortcutId,
            useCount = (current?.useCount ?: 0) + 1,
            lastUsedAt = now
        )
        prefs.edit().putString(KEY_USAGE, gson.toJson(usage.values.toList())).apply()
    }

    fun usageFor(shortcutId: String): TaskShortcutUsage? {
        return readUsage()[shortcutId]
    }

    private fun readUsage(): Map<String, TaskShortcutUsage> {
        val stored = prefs.getString(KEY_USAGE, null).orEmpty()
        if (stored.isBlank()) {
            return emptyMap()
        }
        return runCatching {
            val type = object : TypeToken<List<TaskShortcutUsage>>() {}.type
            gson.fromJson<List<TaskShortcutUsage>>(stored, type)
                .orEmpty()
                .associateBy { it.shortcutId }
        }.getOrDefault(emptyMap())
    }

    companion object {
        private const val PREFS_NAME = "phone_agent_settings"
        private const val ASSET_NAME = "task_shortcuts.json"
        private const val KEY_USAGE = "task_shortcut_usage"
        private val gson = Gson()

        fun parseShortcuts(json: String): List<TaskShortcut> {
            val type = object : TypeToken<List<TaskShortcut>>() {}.type
            return gson.fromJson<List<TaskShortcut>>(json, type)
                .orEmpty()
                .filter { shortcut ->
                    shortcut.id.isNotBlank() &&
                        shortcut.title.isNotBlank() &&
                        shortcut.instruction.isNotBlank()
                }
        }

        fun fallbackShortcuts(): List<TaskShortcut> {
            return listOf(
                TaskShortcut(
                    id = "fallback_wechat",
                    title = "打开微信",
                    instruction = "打开微信",
                    category = "社交",
                    voiceKeywords = listOf("微信")
                ),
                TaskShortcut(
                    id = "fallback_settings_wifi",
                    title = "查看 Wi-Fi",
                    instruction = "打开系统设置查看 Wi-Fi 状态",
                    category = "工具",
                    voiceKeywords = listOf("Wi-Fi")
                ),
                TaskShortcut(
                    id = "fallback_xiaohongshu",
                    title = "小红书搜索",
                    instruction = "打开小红书并搜索咖啡",
                    category = "内容",
                    voiceKeywords = listOf("小红书")
                )
            )
        }
    }
}
