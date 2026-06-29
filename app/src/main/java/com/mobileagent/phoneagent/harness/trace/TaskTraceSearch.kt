package com.mobileagent.phoneagent.harness.trace

data class TaskTraceSearchResult(
    val displayText: String,
    val matchCount: Int,
    val totalLines: Int,
    val query: String
) {
    val filtered: Boolean
        get() = query.isNotBlank()

    fun statusText(): String {
        if (!filtered) {
            return "显示完整日志，共 $totalLines 行"
        }
        return if (matchCount == 0) {
            "未找到：$query"
        } else {
            "找到 $matchCount 处：$query"
        }
    }
}

object TaskTraceSearch {
    fun filter(log: String, query: String, contextLines: Int = DEFAULT_CONTEXT_LINES): TaskTraceSearchResult {
        val lines = log.lines()
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return TaskTraceSearchResult(
                displayText = log,
                matchCount = 0,
                totalLines = lines.size,
                query = ""
            )
        }

        val matchedIndexes = lines
            .mapIndexedNotNull { index, line ->
                if (line.contains(normalizedQuery, ignoreCase = true)) index else null
            }
        if (matchedIndexes.isEmpty()) {
            return TaskTraceSearchResult(
                displayText = "未找到匹配内容：$normalizedQuery",
                matchCount = 0,
                totalLines = lines.size,
                query = normalizedQuery
            )
        }

        val includedIndexes = matchedIndexes
            .flatMap { index ->
                val start = (index - contextLines).coerceAtLeast(0)
                val end = (index + contextLines).coerceAtMost(lines.lastIndex)
                (start..end).toList()
            }
            .distinct()
            .sorted()
        val display = buildString {
            var previousIndex: Int? = null
            includedIndexes.forEach { index ->
                if (previousIndex != null && index > previousIndex!! + 1) {
                    appendLine("…")
                }
                val marker = if (index in matchedIndexes) ">" else " "
                appendLine("$marker ${index + 1}: ${lines[index]}")
                previousIndex = index
            }
        }.trimEnd()
        return TaskTraceSearchResult(
            displayText = display,
            matchCount = matchedIndexes.size,
            totalLines = lines.size,
            query = normalizedQuery
        )
    }

    private const val DEFAULT_CONTEXT_LINES = 1
}
