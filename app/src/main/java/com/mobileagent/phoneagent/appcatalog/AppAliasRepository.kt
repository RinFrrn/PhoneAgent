package com.mobileagent.phoneagent.appcatalog

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class AppAliasRepository(
    private val context: Context,
    private val gson: Gson = Gson()
) {
    fun loadAliases(): List<AppAlias> {
        return runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().use { reader ->
                parseAliases(reader.readText())
            }
        }.getOrDefault(fallbackAliases())
    }

    fun packageHints(appName: String): List<String> {
        return packageHints(loadAliases(), appName)
    }

    companion object {
        private const val ASSET_NAME = "app_aliases.json"
        private val gson = Gson()

        fun parseAliases(json: String): List<AppAlias> {
            val type = object : TypeToken<List<AppAlias>>() {}.type
            return gson.fromJson<List<AppAlias>>(json, type)
                .orEmpty()
                .filter { alias ->
                    alias.id.isNotBlank() &&
                        alias.displayName.isNotBlank() &&
                        alias.packageName.isNotBlank()
                }
        }

        fun packageHints(aliases: List<AppAlias>, appName: String): List<String> {
            val normalizedName = normalizeForMatch(appName)
            if (normalizedName.isBlank()) {
                return emptyList()
            }
            return aliases
                .asSequence()
                .mapNotNull { alias ->
                    val names = buildList {
                        add(alias.displayName)
                        addAll(alias.aliases)
                    }
                    val matched = names.any { name ->
                        val normalizedAlias = normalizeForMatch(name)
                        normalizedAlias == normalizedName ||
                            (normalizedName.length >= 2 && normalizedAlias.contains(normalizedName)) ||
                            (normalizedAlias.length >= 2 && normalizedName.contains(normalizedAlias))
                    }
                    if (matched) alias.packageName else null
                }
                .distinct()
                .toList()
        }

        fun fallbackAliases(): List<AppAlias> {
            return listOf(
                AppAlias("settings", "设置", "com.android.settings", listOf("系统设置", "Settings")),
                AppAlias("wechat", "微信", "com.tencent.mm", listOf("WeChat")),
                AppAlias("alipay", "支付宝", "com.eg.android.AlipayGphone", listOf("Alipay"))
            )
        }

        fun normalizeForMatch(text: String): String {
            return text
                .trim()
                .lowercase()
                .replace("[\\s\\p{Punct}\\p{IsPunctuation}，。、“”‘’【】（）《》·•_\\-]+".toRegex(), "")
        }
    }
}
