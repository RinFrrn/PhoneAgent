package com.mobileagent.phoneagent.harness.trace

import com.google.gson.JsonObject
import com.google.gson.JsonParser

enum class TaskNoteType {
    IMPORTANT_CONTENT,
    TODO_LIST,
    PAGE_NOTE
}

data class TaskNote(
    val type: TaskNoteType,
    val content: String,
    val category: String? = null,
    val reason: String? = null
) {
    fun toDisplayText(): String {
        val label = when (type) {
            TaskNoteType.IMPORTANT_CONTENT -> "重要内容"
            TaskNoteType.TODO_LIST -> "TODO"
            TaskNoteType.PAGE_NOTE -> "页面笔记"
        }
        val categoryText = category?.takeIf { it.isNotBlank() }?.let { "[$it] " }.orEmpty()
        val reasonText = reason?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
        return "$label: $categoryText$content$reasonText"
    }
}

object TaskNoteExtractor {
    fun fromActionJson(actionJson: String): TaskNote? {
        val json = runCatching {
            JsonParser.parseString(actionJson).asJsonObject
        }.getOrNull() ?: return null
        if (json.optString("_metadata") != "do" || json.optString("action") != "Note") {
            return null
        }

        val todos = json.optString("todos").trim()
        val content = json.optString("content").trim()
        val message = json.optString("message").trim()
        val reason = json.optString("reason").trim().takeIf { it.isNotBlank() }
        val category = json.optString("category").trim().takeIf { it.isNotBlank() }
        return when {
            todos.isNotBlank() -> TaskNote(
                type = TaskNoteType.TODO_LIST,
                content = todos,
                category = category,
                reason = reason
            )
            content.isNotBlank() -> TaskNote(
                type = TaskNoteType.IMPORTANT_CONTENT,
                content = content,
                category = category,
                reason = reason
            )
            message.isNotBlank() && !message.equals("true", ignoreCase = true) -> TaskNote(
                type = TaskNoteType.PAGE_NOTE,
                content = message,
                category = category,
                reason = reason
            )
            else -> TaskNote(
                type = TaskNoteType.PAGE_NOTE,
                content = "记录当前页面内容",
                category = category,
                reason = reason
            )
        }
    }

    private fun JsonObject.optString(key: String): String {
        val element = get(key) ?: return ""
        if (element.isJsonNull) {
            return ""
        }
        return runCatching { element.asString }.getOrDefault("")
    }
}
