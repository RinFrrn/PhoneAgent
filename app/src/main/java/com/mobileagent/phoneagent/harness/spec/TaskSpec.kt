package com.mobileagent.phoneagent.harness.spec

data class TaskSpec(
    val id: String,
    val goal: String,
    val mode: String,
    val maxSteps: Int = 30,
    val modelProvider: String? = null,
    val modelDisplayName: String? = null,
    val modelName: String? = null,
    val modelBaseUrl: String? = null
)
