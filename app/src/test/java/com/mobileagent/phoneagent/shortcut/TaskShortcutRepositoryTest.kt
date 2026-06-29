package com.mobileagent.phoneagent.shortcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TaskShortcutRepositoryTest {
    @Test
    fun bundledShortcutAssetParsesValidShortcuts() {
        val json = listOf(
            File("src/main/assets/task_shortcuts.json"),
            File("app/src/main/assets/task_shortcuts.json")
        ).first { it.exists() }.readText()

        val shortcuts = TaskShortcutRepository.parseShortcuts(json)

        assertTrue(shortcuts.size >= 6)
        assertTrue(shortcuts.any { it.id == "sys_wechat_check" && it.instruction.contains("微信") })
        assertTrue(shortcuts.all { it.id.isNotBlank() && it.title.isNotBlank() && it.instruction.isNotBlank() })
    }

    @Test
    fun parserDropsIncompleteShortcutRows() {
        val shortcuts = TaskShortcutRepository.parseShortcuts(
            """
            [
              {"id":"ok","title":"可用","instruction":"打开微信","category":"社交"},
              {"id":"","title":"缺少 id","instruction":"打开微信","category":"社交"},
              {"id":"missing_instruction","title":"缺少指令","instruction":"","category":"工具"}
            ]
            """.trimIndent()
        )

        assertEquals(1, shortcuts.size)
        assertEquals("ok", shortcuts.first().id)
    }
}
