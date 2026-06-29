package com.mobileagent.phoneagent.appcatalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppAliasRepositoryTest {
    @Test
    fun bundledAliasAssetParsesCommonApps() {
        val json = listOf(
            File("src/main/assets/app_aliases.json"),
            File("app/src/main/assets/app_aliases.json")
        ).first { it.exists() }.readText()

        val aliases = AppAliasRepository.parseAliases(json)

        assertTrue(aliases.size >= 10)
        assertTrue(aliases.any { it.displayName == "微信" && it.packageName == "com.tencent.mm" })
        assertTrue(aliases.any { it.aliases.contains("B站") && it.packageName == "tv.danmaku.bili" })
        assertTrue(aliases.all { it.id.isNotBlank() && it.displayName.isNotBlank() && it.packageName.isNotBlank() })
    }

    @Test
    fun packageHintsMatchDisplayNameAndAliases() {
        val aliases = listOf(
            AppAlias(
                id = "wechat",
                displayName = "微信",
                packageName = "com.tencent.mm",
                aliases = listOf("WeChat", "wechat")
            ),
            AppAlias(
                id = "bilibili",
                displayName = "哔哩哔哩",
                packageName = "tv.danmaku.bili",
                aliases = listOf("B站", "bilibili")
            )
        )

        assertEquals(listOf("com.tencent.mm"), AppAliasRepository.packageHints(aliases, "WeChat"))
        assertEquals(listOf("tv.danmaku.bili"), AppAliasRepository.packageHints(aliases, "打开B站"))
    }

    @Test
    fun parserDropsIncompleteRows() {
        val aliases = AppAliasRepository.parseAliases(
            """
            [
              {"id":"ok","displayName":"微信","packageName":"com.tencent.mm","aliases":[]},
              {"id":"","displayName":"坏数据","packageName":"x","aliases":[]},
              {"id":"missing_package","displayName":"坏数据","packageName":"","aliases":[]}
            ]
            """.trimIndent()
        )

        assertEquals(1, aliases.size)
        assertEquals("ok", aliases.first().id)
    }
}
