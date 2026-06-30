package com.mobileagent.phoneagent.action

import org.junit.Assert.assertTrue
import org.junit.Test

class StandardKeyMapperTest {
    @Test
    fun mapsNavigationKeysToTypedActions() {
        assertTrue(StandardKeyMapper.fromKey("back") is BackAction)
        assertTrue(StandardKeyMapper.fromKey("home") is HomeAction)
    }

    @Test
    fun mapsRecentAliasesToRecentAppsAction() {
        assertTrue(StandardKeyMapper.fromKey("recent") is RecentAppsAction)
        assertTrue(StandardKeyMapper.fromKey("recents") is RecentAppsAction)
        assertTrue(StandardKeyMapper.fromKey("overview") is RecentAppsAction)
    }

    @Test
    fun keepsUnknownKeysAsUnsupportedPressKey() {
        val action = StandardKeyMapper.fromKey("menu")

        assertTrue(action is UnknownAction)
        assertTrue((action as UnknownAction).type == "press_key")
    }
}
