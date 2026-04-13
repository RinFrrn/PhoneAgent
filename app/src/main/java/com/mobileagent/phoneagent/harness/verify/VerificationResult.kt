package com.mobileagent.phoneagent.harness.verify

data class VerificationResult(
    val passed: Boolean,
    val confidence: Float,
    val reason: String,
    val observedChange: String? = null
)
