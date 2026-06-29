package com.mobileagent.phoneagent.appcatalog

data class AppAlias(
    val id: String,
    val displayName: String,
    val packageName: String,
    val aliases: List<String> = emptyList(),
    val category: String = ""
)
