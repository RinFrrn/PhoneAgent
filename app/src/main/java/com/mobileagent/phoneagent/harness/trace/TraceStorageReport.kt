package com.mobileagent.phoneagent.harness.trace

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TraceStorageReport(
    val traceFileCount: Int,
    val historyCount: Int,
    val totalBytes: Long,
    val largestTraceBytes: Long,
    val oldestTraceAt: Long?,
    val newestTraceAt: Long?,
    val historyReadable: Boolean,
    val warnings: List<String>
) {
    fun hasWarnings(): Boolean = warnings.isNotEmpty()

    fun toDisplayText(): String {
        return "Trace 存储: $traceFileCount 个文件 · ${formatBytes(totalBytes)} · 历史 $historyCount 条"
    }

    fun detailText(): String {
        val warningText = if (warnings.isEmpty()) {
            "无明显异常"
        } else {
            warnings.joinToString("；")
        }
        val timeText = when {
            oldestTraceAt != null && newestTraceAt != null ->
                "${formatTime(oldestTraceAt)} 至 ${formatTime(newestTraceAt)}"
            newestTraceAt != null -> "最新 ${formatTime(newestTraceAt)}"
            else -> "暂无 trace 文件"
        }
        return buildString {
            appendLine(toDisplayText())
            appendLine("时间范围: $timeText")
            appendLine("最大单文件: ${formatBytes(largestTraceBytes)}")
            appendLine("存储提示: $warningText")
        }.trimEnd()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) {
            return "${bytes}B"
        }
        val kib = bytes / 1024.0
        if (kib < 1024.0) {
            return String.format(Locale.US, "%.1fKB", kib)
        }
        val mib = kib / 1024.0
        return String.format(Locale.US, "%.1fMB", mib)
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }
}

object TraceStorageInspector {
    fun inspect(filesDir: File): TraceStorageReport {
        val traceFiles = traceFiles(filesDir)
        val historyFile = File(filesDir, HISTORY_FILE_NAME)
        val historyRead = readHistoryCount(historyFile)
        val traceBytes = traceFiles.sumOf { it.length() }
        val historyBytes = if (historyFile.isFile) historyFile.length() else 0L
        val modifiedTimes = traceFiles
            .mapNotNull { file -> file.lastModified().takeIf { it > 0L } }

        val warnings = buildWarnings(
            traceFileCount = traceFiles.size,
            historyCount = historyRead.count,
            totalBytes = traceBytes + historyBytes,
            historyReadable = historyRead.readable
        )

        return TraceStorageReport(
            traceFileCount = traceFiles.size,
            historyCount = historyRead.count,
            totalBytes = traceBytes + historyBytes,
            largestTraceBytes = traceFiles.maxOfOrNull { it.length() } ?: 0L,
            oldestTraceAt = modifiedTimes.minOrNull(),
            newestTraceAt = modifiedTimes.maxOrNull(),
            historyReadable = historyRead.readable,
            warnings = warnings
        )
    }

    private fun traceFiles(filesDir: File): List<File> {
        val traceRoot = File(filesDir, TRACE_DIR_NAME)
        if (!traceRoot.exists()) {
            return emptyList()
        }
        return traceRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .toList()
    }

    private fun readHistoryCount(historyFile: File): HistoryRead {
        if (!historyFile.exists()) {
            return HistoryRead(count = 0, readable = true)
        }
        return runCatching {
            val type = object : TypeToken<List<TaskHistoryEntry>>() {}.type
            val history = Gson().fromJson<List<TaskHistoryEntry>>(historyFile.readText(), type)
            HistoryRead(count = history?.size ?: 0, readable = true)
        }.getOrDefault(HistoryRead(count = 0, readable = false))
    }

    private fun buildWarnings(
        traceFileCount: Int,
        historyCount: Int,
        totalBytes: Long,
        historyReadable: Boolean
    ): List<String> {
        val warnings = mutableListOf<String>()
        if (!historyReadable) {
            warnings += "历史索引读取失败"
        }
        if (traceFileCount >= MANY_TRACE_FILES) {
            warnings += "Trace 文件较多，建议导出后清理旧任务"
        }
        if (totalBytes >= LARGE_TRACE_BYTES) {
            warnings += "Trace 存储已超过 ${LARGE_TRACE_BYTES / 1024 / 1024}MB"
        }
        if (historyCount >= HISTORY_LIMIT_WARNING) {
            warnings += "历史条数接近保留上限"
        }
        return warnings
    }

    private data class HistoryRead(
        val count: Int,
        val readable: Boolean
    )

    private const val TRACE_DIR_NAME = "harness-traces"
    private const val HISTORY_FILE_NAME = "harness-history.json"
    private const val MANY_TRACE_FILES = 500
    private const val LARGE_TRACE_BYTES = 50L * 1024L * 1024L
    private const val HISTORY_LIMIT_WARNING = 180
}
