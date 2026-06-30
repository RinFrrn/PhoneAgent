package com.mobileagent.phoneagent.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardSwipeMapperTest {
    @Test
    fun mapsUpDirectionToVerticalSwipe() {
        val swipe = StandardSwipeMapper.fromDirection("up")

        requireNotNull(swipe)
        assertEquals(500, swipe.startX)
        assertTrue(swipe.startY > swipe.endY)
    }

    @Test
    fun mapsScrollValueToDirection() {
        val up = StandardSwipeMapper.fromScroll(anchorX = 500, anchorY = 540, value = 360)
        val down = StandardSwipeMapper.fromScroll(anchorX = 500, anchorY = 540, value = -360)

        requireNotNull(up)
        requireNotNull(down)
        assertTrue(up.startY > up.endY)
        assertTrue(down.startY < down.endY)
    }

    @Test
    fun ignoresUnknownOrZeroMovement() {
        assertNull(StandardSwipeMapper.fromDirection("diagonal"))
        assertNull(StandardSwipeMapper.fromScroll(anchorX = 500, anchorY = 540, value = 0))
    }
}
