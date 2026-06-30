package com.mobileagent.phoneagent.harness.trace

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Locale

data class TraceCleanupReport(
    val candidateCount: Int,
    val deletedCount: Int,
    val reclaimedBytes: Long,
    val failedCount: Int,
    val cutoffTime: Long,
    val dryRun: Boolean
) {
    fun hasCandidates(): Boolean = candidateCount > 0

    fun toDisplayText(): String {
        val actionText = if (dryRun) {
            "可清理"
        } else {
            "已清理"
        }
        val actionCount = if (dryRun) candidateCount else deletedCount
        val failureText = if (failedCount > 0) {
            " · 失败 $failedCount 个"
        } else {
            ""
        }
        return "Trace 维护: $actionText $actionCount 个孤立文件 · ${formatBytes(reclaimedBytes)}$failureText"
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
}

object TraceStorageMaintenance {
    const val DEFAULT_ORPHAN_RETENTION_DAYS = 30L

    fun previewOrphanCleanup(
        filesDir: File,
        now: Long = System.currentTimeMillis(),
        olderThanMs: Long = DEFAULT_ORPHAN_RETENTION_MS
    ): TraceCleanupReport {
        return cleanupOrphanTraces(
            filesDir = filesDir,
            now = now,
            olderThanMs = olderThanMs,
            dryRun = true
        )
    }

    fun cleanupOrphanTraces(
        filesDir: File,
        now: Long = System.currentTimeMillis(),
        olderThanMs: Long = DEFAULT_ORPHAN_RETENTION_MS,
        dryRun: Boolean = false
    ): TraceCleanupReport {
        val cutoffTime = now - olderThanMs
        val candidates = orphanTraceFiles(filesDir, cutoffTime)
        if (dryRun) {
            return TraceCleanupReport(
                candidateCount = candidates.size,
                deletedCount = 0,
                reclaimedBytes = candidates.sumOf { it.length() },
                failedCount = 0,
                cutoffTime = cutoffTime,
                dryRun = true
            )
        }

        var deletedCount = 0
        var reclaimedBytes = 0L
        var failedCount = 0
        candidates.forEach { file ->
            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                deletedCount += 1
                reclaimedBytes += size
            } else {
                failedCount += 1
            }
        }
        pruneEmptyTraceDirs(filesDir)
        return TraceCleanupReport(
            candidateCount = candidates.size,
            deletedCount = deletedCount,
            reclaimedBytes = reclaimedBytes,
            failedCount = failedCount,
            cutoffTime = cutoffTime,
            dryRun = false
        )
    }

    private fun orphanTraceFiles(filesDir: File, cutoffTime: Long): List<File> {
        val protectedSessionIds = readHistory(filesDir)
            .flatMap { listOf(it.sessionId, it.traceSessionId) }
            .filter { it.isNotBlank() }
            .toSet()
        val traceRoot = File(filesDir, TRACE_DIR_NAME)
        if (!traceRoot.exists()) {
            return emptyList()
        }
        return traceRoot.walkTopDown()
            .filter { file ->
                file.isFile &&
                    file.name.startsWith("session-") &&
                    file.extension.equals("json", ignoreCase = true) &&
                    file.lastModified() in 1L..cutoffTime &&
                    sessionIdFromTraceFile(file) !in protectedSessionIds
            }
            .toList()
    }

    private fun readHistory(filesDir: File): List<TaskHistoryEntry> {
        val historyFile = File(filesDir, HISTORY_FILE_NAME)
        if (!historyFile.exists()) {
            return emptyList()
        }
        return runCatching {
            val type = object : TypeToken<List<TaskHistoryEntry>>() {}.type
            Gson().fromJson<List<TaskHistoryEntry>>(historyFile.readText(), type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun sessionIdFromTraceFile(file: File): String {
        return file.name
            .removePrefix("session-")
            .removeSuffix(".json")
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

    private const val TRACE_DIR_NAME = "harness-traces"
    private const val HISTORY_FILE_NAME = "harness-history.json"
    private const val DEFAULT_ORPHAN_RETENTION_MS = DEFAULT_ORPHAN_RETENTION_DAYS * 24L * 60L * 60L * 1000L
}
