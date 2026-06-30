package com.mobileagent.phoneagent.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardKeyEventMapperTest {
    @Test
    fun mapsNavigationKeyEventsToNavigationActions() {
        assertTrue(StandardKeyEventMapper.fromKey("back") is BackAction)
        assertTrue(StandardKeyEventMapper.fromKey("home") is HomeAction)
        assertTrue(StandardKeyEventMapper.fromKey("recent") is RecentAppsAction)
    }

    @Test
    fun mapsSupportedSystemEventsToTypedActions() {
        assertSystemEvent("notifications", StandardSystemKeyEvent.NOTIFICATIONS)
        assertSystemEvent("quick-settings", StandardSystemKeyEvent.QUICK_SETTINGS)
        assertSystemEvent("KEYCODE_POWER", StandardSystemKeyEvent.POWER_DIALOG)
        assertSystemEvent("lock_screen", StandardSystemKeyEvent.LOCK_SCREEN)
    }

    @Test
    fun keepsUnsupportedHardwareKeysUnknown() {
        val action = StandardKeyEventMapper.fromKey("volume_up")

        assertTrue(action is UnknownAction)
        assertEquals("key_event", (action as UnknownAction).type)
    }

    private fun assertSystemEvent(key: String, event: StandardSystemKeyEvent) {
        val action = StandardKeyEventMapper.fromKey(key)

        assertTrue(action is KeyEventAction)
        assertEquals(event, (action as KeyEventAction).event)
    }
}
