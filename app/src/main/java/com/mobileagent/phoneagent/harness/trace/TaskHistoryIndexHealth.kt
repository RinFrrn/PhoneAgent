package com.mobileagent.phoneagent.harness.trace

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

enum class TaskHistoryIndexIssue {
    MISSING_TRACE,
    STALE_RUNNING
}

data class TaskHistoryEntryHealth(
    val sessionId: String,
    val traceSessionId: String,
    val issues: List<TaskHistoryIndexIssue>
) {
    fun hasIssues(): Boolean = issues.isNotEmpty()

    fun toDisplaySuffix(): String {
        if (issues.isEmpty()) {
            return ""
        }
        return issues.joinToString(prefix = " · ", separator = " · ") { issue ->
            when (issue) {
                TaskHistoryIndexIssue.MISSING_TRACE -> "trace 缺失"
                TaskHistoryIndexIssue.STALE_RUNNING -> "运行超时"
            }
        }
    }
}

data class TaskHistoryIndexHealthReport(
    val totalCount: Int,
    val missingTraceCount: Int,
    val staleRunningCount: Int,
    val historyReadable: Boolean,
    val entries: List<TaskHistoryEntryHealth>
) {
    fun hasIssues(): Boolean {
        return !historyReadable || missingTraceCount > 0 || staleRunningCount > 0
    }

    fun issueFor(entry: TaskHistoryEntry): TaskHistoryEntryHealth? {
        return entries.firstOrNull { it.sessionId == entry.sessionId }
    }

    fun toDisplayText(): String {
        if (!historyReadable) {
            return "历史索引：读取失败"
        }
        if (totalCount == 0) {
            return "历史索引：暂无记录"
        }
        if (!hasIssues()) {
            return "历史索引：$totalCount 条 · 正常"
        }
        val parts = mutableListOf<String>()
        if (missingTraceCount > 0) {
            parts += "trace 缺失 $missingTraceCount"
        }
        if (staleRunningCount > 0) {
            parts += "运行超时 $staleRunningCount"
        }
        return "历史索引：$totalCount 条 · ${parts.joinToString(" · ")}"
    }
}

object TaskHistoryIndexHealthInspector {
    fun inspect(
        filesDir: File,
        now: Long = System.currentTimeMillis(),
        staleRunningMs: Long = DEFAULT_STALE_RUNNING_MS
    ): TaskHistoryIndexHealthReport {
        val historyRead = readHistory(filesDir)
        if (!historyRead.readable) {
            return TaskHistoryIndexHealthReport(
                totalCount = 0,
                missingTraceCount = 0,
                staleRunningCount = 0,
                historyReadable = false,
                entries = emptyList()
            )
        }

        val traceSessionIds = readTraceSessionIds(filesDir)
        val entryHealth = historyRead.entries.map { entry ->
            val issues = mutableListOf<TaskHistoryIndexIssue>()
            val hasFinished = entry.status != TaskHistoryStatus.RUNNING
            if (hasFinished && entry.traceSessionId !in traceSessionIds) {
                issues += TaskHistoryIndexIssue.MISSING_TRACE
            }
            if (entry.status == TaskHistoryStatus.RUNNING && now - entry.startedAt >= staleRunningMs) {
                issues += TaskHistoryIndexIssue.STALE_RUNNING
            }
            TaskHistoryEntryHealth(
                sessionId = entry.sessionId,
                traceSessionId = entry.traceSessionId,
                issues = issues
            )
        }
        return TaskHistoryIndexHealthReport(
            totalCount = historyRead.entries.size,
            missingTraceCount = entryHealth.count { TaskHistoryIndexIssue.MISSING_TRACE in it.issues },
            staleRunningCount = entryHealth.count { TaskHistoryIndexIssue.STALE_RUNNING in it.issues },
            historyReadable = true,
            entries = entryHealth
        )
    }

    private fun readHistory(filesDir: File): HistoryRead {
        val historyFile = File(filesDir, HISTORY_FILE_NAME)
        if (!historyFile.exists()) {
            return HistoryRead(entries = emptyList(), readable = true)
        }
        return runCatching {
            val type = object : TypeToken<List<TaskHistoryEntry>>() {}.type
            HistoryRead(
                entries = Gson().fromJson<List<TaskHistoryEntry>>(historyFile.readText(), type) ?: emptyList(),
                readable = true
            )
        }.getOrDefault(HistoryRead(entries = emptyList(), readable = false))
    }

    private fun readTraceSessionIds(filesDir: File): Set<String> {
        val traceRoot = File(filesDir, TRACE_DIR_NAME)
        if (!traceRoot.exists()) {
            return emptySet()
        }
        return traceRoot.walkTopDown()
            .filter { it.isFile && it.name.startsWith("session-") && it.extension.equals("json", ignoreCase = true) }
            .map { it.name.removePrefix("session-").removeSuffix(".json") }
            .toSet()
    }

    private data class HistoryRead(
        val entries: List<TaskHistoryEntry>,
        val readable: Boolean
    )

    private const val TRACE_DIR_NAME = "harness-traces"
    private const val HISTORY_FILE_NAME = "harness-history.json"
    private const val DEFAULT_STALE_RUNNING_MS = 6L * 60L * 60L * 1000L
}
