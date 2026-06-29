package com.mobileagent.phoneagent.harness.act

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ExecutionHumanizerTest {
    @Test
    fun disabledProfileLeavesActionUntouchedAndUntraced() = runBlocking {
        val humanizer = ExecutionHumanizer(
            profile = ExecutionHumanizationProfile(enabled = false),
            random = Random(1)
        )
        val action = """{"_metadata":"do","action":"Tap","element":[500,500]}"""

        val result = humanizer.humanize(action)

        assertEquals(action, result.actionJson)
        assertNull(result.trace)
    }

    @Test
    fun positionRandomizationAdjustsTapWithinRelativeBounds() = runBlocking {
        val humanizer = ExecutionHumanizer(
            profile = ExecutionHumanizationProfile(
                enabled = true,
                timeRandomEnabled = false,
                positionRandomEnabled = true,
                positionOffsetPercentage = 0.02f
            ),
            random = Random(2)
        )
        val action = """{"_metadata":"do","action":"Tap","element":[500,500]}"""

        val result = humanizer.humanize(action)
        val (x, y) = readPoint(result.actionJson, "element")

        assertNotEquals(action, result.actionJson)
        assertTrue(x in 480..520)
        assertTrue(y in 480..520)
        assertTrue(result.trace?.positionRandomized == true)
        assertEquals(0L, result.trace?.delayMs)
    }

    @Test
    fun unsupportedActionKeepsJsonButRecordsReasonWhenEnabled() = runBlocking {
        val humanizer = ExecutionHumanizer(
            profile = ExecutionHumanizationProfile(enabled = true),
            random = Random(3)
        )
        val action = """{"_metadata":"do","action":"Home"}"""

        val result = humanizer.humanize(action)

        assertEquals(action, result.actionJson)
        assertFalse(result.trace?.positionRandomized == true)
        assertTrue(result.trace?.reason?.contains("无需人性化处理") == true)
    }

    @Test
    fun settingsConvertOffsetPercentSafely() {
        assertEquals(0.02f, ExecutionHumanizationSettings.offsetPercentToFraction("2"), 0.0001f)
        assertEquals(0.025f, ExecutionHumanizationSettings.offsetPercentToFraction("2.5%"), 0.0001f)
        assertEquals(0.1f, ExecutionHumanizationSettings.offsetPercentToFraction("99"), 0.0001f)
        assertEquals(0.02f, ExecutionHumanizationSettings.offsetPercentToFraction("bad"), 0.0001f)
        assertEquals("2", ExecutionHumanizationSettings.offsetFractionToPercentText(0.02f))
        assertEquals("2.5", ExecutionHumanizationSettings.offsetFractionToPercentText(0.025f))
    }

    private fun readPoint(json: String, fieldName: String): Pair<Int, Int> {
        val match = Regex(""""$fieldName"\s*:\s*\[\s*(\d+)\s*,\s*(\d+)\s*]""")
            .find(json)
        val x = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        val y = match?.groupValues?.getOrNull(2)?.toIntOrNull()
        require(x != null && y != null) { "missing $fieldName in $json" }
        return x to y
    }
}
