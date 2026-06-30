package com.mobileagent.phoneagent.harness.trace

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File

enum class TaskHistoryDeleteStatus {
    DELETED,
    NOT_FOUND,
    RUNNING,
    HISTORY_UNREADABLE,
    HISTORY_WRITE_FAILED
}

data class TaskHistoryDeleteResult(
    val status: TaskHistoryDeleteStatus,
    val sessionId: String,
    val traceSessionId: String? = null,
    val traceDeleted: Boolean = false
) {
    fun success(): Boolean = status == TaskHistoryDeleteStatus.DELETED

    fun toDisplayText(): String {
        return when (status) {
            TaskHistoryDeleteStatus.DELETED ->
                if (traceDeleted) "已删除任务历史和 Trace 文件" else "已删除任务历史，Trace 文件不存在"
            TaskHistoryDeleteStatus.NOT_FOUND -> "未找到任务历史"
            TaskHistoryDeleteStatus.RUNNING -> "运行中的任务不能删除，请先停止任务"
            TaskHistoryDeleteStatus.HISTORY_UNREADABLE -> "历史索引读取失败，未删除"
            TaskHistoryDeleteStatus.HISTORY_WRITE_FAILED -> "历史索引写入失败，未删除 Trace"
        }
    }
}

object TaskHistoryMaintenance {
    fun deleteFinishedEntry(filesDir: File, sessionId: String): TaskHistoryDeleteResult {
        val historyFile = File(filesDir, HISTORY_FILE_NAME)
        val history = readHistory(historyFile)
            ?: return TaskHistoryDeleteResult(
                status = TaskHistoryDeleteStatus.HISTORY_UNREADABLE,
                sessionId = sessionId
            )
        val entry = history.firstOrNull { it.sessionId == sessionId || it.traceSessionId == sessionId }
            ?: return TaskHistoryDeleteResult(
                status = TaskHistoryDeleteStatus.NOT_FOUND,
                sessionId = sessionId
            )

        if (entry.status == TaskHistoryStatus.RUNNING) {
            return TaskHistoryDeleteResult(
                status = TaskHistoryDeleteStatus.RUNNING,
                sessionId = entry.sessionId,
                traceSessionId = entry.traceSessionId
            )
        }

        val updatedHistory = history.filterNot { it.sessionId == entry.sessionId }
        val writeSucceeded = runCatching {
            historyFile.writeText(gson.toJson(updatedHistory))
            true
        }.getOrDefault(false)
        if (!writeSucceeded) {
            return TaskHistoryDeleteResult(
                status = TaskHistoryDeleteStatus.HISTORY_WRITE_FAILED,
                sessionId = entry.sessionId,
                traceSessionId = entry.traceSessionId
            )
        }

        val traceFile = findSessionFile(filesDir, entry.traceSessionId)
        val traceDeleted = traceFile?.let { runCatching { it.delete() }.getOrDefault(false) } ?: false
        pruneEmptyTraceDirs(filesDir)
        return TaskHistoryDeleteResult(
            status = TaskHistoryDeleteStatus.DELETED,
            sessionId = entry.sessionId,
            traceSessionId = entry.traceSessionId,
            traceDeleted = traceDeleted
        )
    }

    private fun readHistory(historyFile: File): List<TaskHistoryEntry>? {
        if (!historyFile.exists()) {
            return emptyList()
        }
        return runCatching {
            val type = object : TypeToken<List<TaskHistoryEntry>>() {}.type
            gson.fromJson<List<TaskHistoryEntry>>(historyFile.readText(), type) ?: emptyList()
        }.getOrNull()
    }

    private fun findSessionFile(filesDir: File, sessionId: String): File? {
        val root = File(filesDir, TRACE_DIR_NAME)
        if (!root.exists()) {
            return null
        }
        return root.walkTopDown()
            .firstOrNull { it.isFile && it.name == "session-$sessionId.json" }
    }

    private fun pruneEmptyTraceDirs(filesDir: File) {
        val traceRoot = File(filesDir, TRACE_DIR_NAME)
        if (!traceRoot.exists()) {
            return
        }
        traceRoot.walkBottomUp()
            .filter { it.isDirectory && it != traceRoot && it.listFiles().orEmpty().isEmpty() }
            .forEach { directory -> runCatching { directory.delete() } }
    }

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private const val TRACE_DIR_NAME = "harness-traces"
    private const val HISTORY_FILE_NAME = "harness-history.json"
}
