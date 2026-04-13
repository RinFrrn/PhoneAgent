package com.mobileagent.phoneagent.harness.spec

data class TaskSpec(
    val id: String,
    val goal: String,
    val mode: String,
    val maxSteps: Int = 30
)
