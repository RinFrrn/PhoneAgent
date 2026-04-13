package com.mobileagent.phoneagent.harness.observe

import com.mobileagent.phoneagent.model.ContentItem

data class Observation(
    val currentApp: String?,
    val contentItems: List<ContentItem>,
    val failureMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
