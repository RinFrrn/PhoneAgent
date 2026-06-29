package com.mobileagent.phoneagent.harness.trace

data class TaskNoteSummary(
    val notes: List<TaskNote>
) {
    fun isEmpty(): Boolean = notes.isEmpty()

    fun toDisplayText(): String {
        if (isEmpty()) {
            return "任务笔记: 未记录"
        }
        return buildString {
            appendLine("任务笔记: ${notes.size} 条")
            notes.forEachIndexed { index, note ->
                appendLine("${index + 1}. ${note.toDisplayText()}")
            }
        }.trimEnd()
    }
}

object TaskNoteSummaryBuilder {
    fun summarize(session: SessionTrace): TaskNoteSummary {
        return TaskNoteSummary(
            notes = session.steps.mapNotNull { it.execution?.taskNote }
        )
    }
}
