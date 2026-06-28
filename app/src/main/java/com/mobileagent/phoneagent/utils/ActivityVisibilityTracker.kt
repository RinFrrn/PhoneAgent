package com.mobileagent.phoneagent.utils

object ActivityVisibilityTracker {
    @Volatile
    private var visibleCount: Int = 0

    fun markStarted() {
        visibleCount += 1
    }

    fun markStopped() {
        visibleCount = (visibleCount - 1).coerceAtLeast(0)
    }

    fun isAppVisible(): Boolean = visibleCount > 0
}
