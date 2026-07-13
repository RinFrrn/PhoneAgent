package com.mobileagent.phoneagent.harness.trace

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.mobileagent.phoneagent.harness.recover.FailureType
import java.io.File
import java.util.concurrent.CompletableFuture

data class TraceMigrationReport(
    val scannedTraceCount: Int,
    val migratedTraceCount: Int,
    val failedTraceCount: Int,
    val migratedHistoryEntryCount: Int,
    val historyReadable: Boolean,
    val historyWriteFailed: Boolean
)

data class InterruptedSessionRecoveryReport(
    val candidateCount: Int,
    val recoveredCount: Int,
    val updatedTraceCount: Int,
    val missingTraceCount: Int,
    val failedTraceCount: Int,
    val historyReadable: Boolean,
    val historyWriteFailed: Boolean
)

data class TraceStorageStartupReport(
    val migration: TraceMigrationReport,
    val recovery: InterruptedSessionRecoveryReport
) {
    fun toLogText(): String {
        return "Trace 启动维护: 迁移 ${migration.migratedTraceCount}/${migration.scannedTraceCount}" +
            "，迁移失败 ${migration.failedTraceCount}" +
            "，收敛中断会话 ${recovery.recoveredCount}/${recovery.candidateCount}" +
            "，Trace 缺失 ${recovery.missingTraceCount}" +
            "，更新失败 ${recovery.failedTraceCount}"
    }
}

object TraceStorageStartup {
    @Volatile
    private var startupFuture: CompletableFuture<TraceStorageStartupReport>? = null

    @Synchronized
    fun startAsync(filesDir: File): CompletableFuture<TraceStorageStartupReport> {
        startupFuture?.let { return it }
        val future = CompletableFuture<TraceStorageStartupReport>()
        startupFuture = future
        Thread(
            {
                runCatching { prepare(filesDir) }
                    .onSuccess { report -> future.complete(report) }
                    .onFailure { error -> future.completeExceptionally(error) }
            },
            STARTUP_THREAD_NAME
        ).start()
        return future
    }

    fun awaitStartupMaintenance() {
        startupFuture?.let { future ->
            runCatching { future.get() }
        }
    }

    fun prepare(
        filesDir: File,
        now: Long = System.currentTimeMillis()
    ): TraceStorageStartupReport {
        val migrationMarker = File(filesDir, MIGRATION_MARKER_FILE_NAME)
        val migration = if (migrationMarker.exists()) {
            TraceMigrationReport(
                scannedTraceCount = 0,
                migratedTraceCount = 0,
                failedTraceCount = 0,
                migratedHistoryEntryCount = 0,
                historyReadable = true,
                historyWriteFailed = false
            )
        } else {
            migrateLegacyStorage(filesDir).also { report ->
                if (
                    report.failedTraceCount == 0 &&
                    report.historyReadable &&
                    !report.historyWriteFailed
                ) {
                    runCatching {
                        TraceFileWriter.writeAtomically(
                            migrationMarker,
                            TraceSanitizer.DATA_POLICY
                        )
                    }
                }
            }
        }
        val recovery = recoverInterruptedSessions(filesDir, now)
        return TraceStorageStartupReport(migration = migration, recovery = recovery)
    }

    fun migrateLegacyStorage(filesDir: File): TraceMigrationReport {
        val traceFiles = sessionTraceFiles(filesDir)
        var migratedTraceCount = 0
        var failedTraceCount = 0
        val migratedSessions = linkedMapOf<String, SessionTrace>()

        traceFiles.forEach { file ->
            runCatching {
                val original = gson.fromJson(file.readText(), SessionTrace::class.java)
                    ?: error("Trace 内容为空")
                val migrated = TraceSanitizer.sanitizeSession(original)
                migratedSessions[migrated.sessionId] = migrated
                if (migrated != original) {
                    TraceFileWriter.writeAtomically(file, gson.toJson(migrated))
                    migratedTraceCount += 1
                }
            }.onFailure {
                failedTraceCount += 1
            }
        }

        val historyFile = File(filesDir, HISTORY_FILE_NAME)
        val history = readHistory(historyFile)
        if (history == null) {
            return TraceMigrationReport(
                scannedTraceCount = traceFiles.size,
                migratedTraceCount = migratedTraceCount,
                failedTraceCount = failedTraceCount,
                migratedHistoryEntryCount = 0,
                historyReadable = false,
                historyWriteFailed = false
            )
        }

        var migratedHistoryEntryCount = 0
        val migratedHistory = history.map { entry ->
            val sanitized = TraceSanitizer.sanitizeHistoryEntry(entry)
            val linkedTrace = migratedSessions[entry.traceSessionId] ?: migratedSessions[entry.sessionId]
            val migrated = if (linkedTrace == null) {
                sanitized
            } else {
                sanitized.copy(
                    taskGoal = linkedTrace.taskGoal,
                    outcomeMessage = linkedTrace.outcomeMessage ?: sanitized.outcomeMessage,
                    totalSteps = linkedTrace.totalSteps
                )
            }
            if (migrated != entry) {
                migratedHistoryEntryCount += 1
            }
            migrated
        }
        val historyWriteFailed = migratedHistoryEntryCount > 0 && runCatching {
            TraceFileWriter.writeAtomically(historyFile, gson.toJson(migratedHistory))
        }.isFailure

        return TraceMigrationReport(
            scannedTraceCount = traceFiles.size,
            migratedTraceCount = migratedTraceCount,
            failedTraceCount = failedTraceCount,
            migratedHistoryEntryCount = migratedHistoryEntryCount,
            historyReadable = true,
            historyWriteFailed = historyWriteFailed
        )
    }

    fun recoverInterruptedSessions(
        filesDir: File,
        now: Long = System.currentTimeMillis()
    ): InterruptedSessionRecoveryReport {
        val historyFile = File(filesDir, HISTORY_FILE_NAME)
        val history = readHistory(historyFile)
            ?: return InterruptedSessionRecoveryReport(
                candidateCount = 0,
                recoveredCount = 0,
                updatedTraceCount = 0,
                missingTraceCount = 0,
                failedTraceCount = 0,
                historyReadable = false,
                historyWriteFailed = false
            )
        val candidates = history.filter { it.status == TaskHistoryStatus.RUNNING }
        if (candidates.isEmpty()) {
            return InterruptedSessionRecoveryReport(
                candidateCount = 0,
                recoveredCount = 0,
                updatedTraceCount = 0,
                missingTraceCount = 0,
                failedTraceCount = 0,
                historyReadable = true,
                historyWriteFailed = false
            )
        }

        var updatedTraceCount = 0
        var missingTraceCount = 0
        var failedTraceCount = 0
        val stepCounts = mutableMapOf<String, Int>()
        candidates.forEach { entry ->
            val traceFile = findSessionFile(filesDir, entry.traceSessionId)
                ?: findSessionFile(filesDir, entry.sessionId)
            if (traceFile == null) {
                missingTraceCount += 1
            } else {
                runCatching {
                    val trace = gson.fromJson(traceFile.readText(), SessionTrace::class.java)
                        ?: error("Trace 内容为空")
                    val recovered = TraceSanitizer.sanitizeSession(trace).copy(
                        completedAt = now,
                        success = false,
                        outcomeMessage = INTERRUPTED_OUTCOME,
                        totalSteps = trace.steps.size,
                        status = TaskHistoryStatus.STOPPED,
                        dataPolicy = TraceSanitizer.DATA_POLICY,
                        failureType = FailureType.RUNTIME_INTERRUPTED
                    )
                    stepCounts[entry.sessionId] = recovered.totalSteps
                    TraceFileWriter.writeAtomically(traceFile, gson.toJson(recovered))
                    updatedTraceCount += 1
                }.onFailure {
                    failedTraceCount += 1
                }
            }
        }

        val candidateIds = candidates.mapTo(hashSetOf()) { it.sessionId }
        val recoveredHistory = history.map { entry ->
            if (entry.sessionId !in candidateIds) {
                TraceSanitizer.sanitizeHistoryEntry(entry)
            } else {
                TraceSanitizer.sanitizeHistoryEntry(entry).copy(
                    completedAt = now,
                    status = TaskHistoryStatus.STOPPED,
                    success = false,
                    outcomeMessage = INTERRUPTED_OUTCOME,
                    totalSteps = stepCounts[entry.sessionId] ?: entry.totalSteps,
                    failureType = FailureType.RUNTIME_INTERRUPTED
                )
            }
        }
        val historyWriteFailed = runCatching {
            TraceFileWriter.writeAtomically(historyFile, gson.toJson(recoveredHistory))
        }.isFailure

        return InterruptedSessionRecoveryReport(
            candidateCount = candidates.size,
            recoveredCount = if (historyWriteFailed) 0 else candidates.size,
            updatedTraceCount = updatedTraceCount,
            missingTraceCount = missingTraceCount,
            failedTraceCount = failedTraceCount,
            historyReadable = true,
            historyWriteFailed = historyWriteFailed
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

    private fun sessionTraceFiles(filesDir: File): List<File> {
        val traceRoot = File(filesDir, TRACE_DIR_NAME)
        if (!traceRoot.exists()) {
            return emptyList()
        }
        return traceRoot.walkTopDown()
            .filter { file ->
                file.isFile && file.name.startsWith("session-") &&
                    file.extension.equals("json", ignoreCase = true)
            }
            .toList()
    }

    private fun findSessionFile(filesDir: File, sessionId: String): File? {
        return sessionTraceFiles(filesDir)
            .firstOrNull { it.name == "session-$sessionId.json" }
    }

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private const val TRACE_DIR_NAME = "harness-traces"
    private const val HISTORY_FILE_NAME = "harness-history.json"
    private const val MIGRATION_MARKER_FILE_NAME = ".trace-migration-minimized-v1"
    private const val INTERRUPTED_OUTCOME = "应用进程中断，任务未正常结束。"
    private const val STARTUP_THREAD_NAME = "trace-storage-startup"
}
