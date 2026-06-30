package com.mobileagent.phoneagent.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardDragMapperTest {
    @Test
    fun mapsStartAndEndPointsToDragAction() {
        val action = StandardDragMapper.fromPoints(
            start = listOf(120, 500),
            end = listOf(880, 500),
            durationMs = 750L
        )

        requireNotNull(action)
        assertEquals(120, action.startX)
        assertEquals(500, action.startY)
        assertEquals(880, action.endX)
        assertEquals(500, action.endY)
        assertEquals(750L, action.durationMs)
    }

    @Test
    fun clampsDurationToGestureSafeRange() {
        val tooShort = StandardDragMapper.fromPoints(listOf(100, 100), listOf(200, 200), durationMs = 1L)
        val tooLong = StandardDragMapper.fromPoints(listOf(100, 100), listOf(200, 200), durationMs = 10_000L)

        requireNotNull(tooShort)
        requireNotNull(tooLong)
        assertEquals(100L, tooShort.durationMs)
        assertEquals(5_000L, tooLong.durationMs)
    }

    @Test
    fun ignoresIncompleteDragPoints() {
        assertNull(StandardDragMapper.fromPoints(start = listOf(100), end = listOf(200, 200)))
        assertNull(StandardDragMapper.fromPoints(start = listOf(100, 100), end = null))
        assertTrue(StandardDragMapper.fromPoints(start = listOf(100, 100), end = listOf(200, 200)) is DragAction)
    }
}
