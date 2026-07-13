package com.mobileagent.phoneagent.harness.trace

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.mobileagent.phoneagent.harness.recover.FailureType
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

interface TraceStore {
    fun openSession(
        taskId: String,
        taskGoal: String,
        mode: String,
        modelProvider: String? = null,
        modelDisplayName: String? = null,
        modelName: String? = null,
        modelBaseUrl: String? = null
    ): String

    fun appendStep(sessionId: String, stepTrace: StepTrace)
    fun closeSession(
        sessionId: String,
        status: TaskHistoryStatus,
        outcomeMessage: String,
        failureType: FailureType? = null
    )

    fun loadRecentHistory(limit: Int = 5): List<TaskHistoryEntry>
    fun loadSession(sessionId: String): SessionTrace?
}

class FileTraceStore(
    private val filesDir: File
) : TraceStore {
    constructor(context: Context) : this(context.filesDir)

    private val tag = "FileTraceStore"
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val sessions = linkedMapOf<String, MutableSessionTrace>()
    private val historyFile = File(filesDir, "harness-history.json")

    override fun openSession(
        taskId: String,
        taskGoal: String,
        mode: String,
        modelProvider: String?,
        modelDisplayName: String?,
        modelName: String?,
        modelBaseUrl: String?
    ): String {
        val sessionId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        val session = MutableSessionTrace(
            sessionId = sessionId,
            taskId = taskId,
            taskGoal = TraceSanitizer.sanitizeText(taskGoal),
            mode = mode,
            modelProvider = TraceSanitizer.sanitizeNullableText(modelProvider),
            modelDisplayName = TraceSanitizer.sanitizeNullableText(modelDisplayName),
            modelName = TraceSanitizer.sanitizeNullableText(modelName),
            modelBaseUrl = TraceSanitizer.sanitizeNullableText(modelBaseUrl),
            startedAt = startedAt
        )
        sessions[sessionId] = session
        writeSnapshot(session.toSnapshot(status = TaskHistoryStatus.RUNNING))
        upsertHistory(
            TaskHistoryEntry(
                sessionId = sessionId,
                taskId = taskId,
                taskGoal = session.taskGoal,
                mode = mode,
                modelProvider = session.modelProvider,
                modelDisplayName = session.modelDisplayName,
                modelName = session.modelName,
                modelBaseUrl = session.modelBaseUrl,
                startedAt = startedAt,
                status = TaskHistoryStatus.RUNNING
            )
        )
        return sessionId
    }

    override fun appendStep(sessionId: String, stepTrace: StepTrace) {
        val session = sessions[sessionId] ?: return
        session.steps.add(TraceSanitizer.sanitizeStep(stepTrace))
        writeSnapshot(session.toSnapshot(status = TaskHistoryStatus.RUNNING))
    }

    override fun closeSession(
        sessionId: String,
        status: TaskHistoryStatus,
        outcomeMessage: String,
        failureType: FailureType?
    ) {
        val session = sessions.remove(sessionId) ?: return
        val completedAt = System.currentTimeMillis()
        val success = status == TaskHistoryStatus.SUCCEEDED
        val sanitizedOutcome = TraceSanitizer.sanitizeText(outcomeMessage)
        val snapshot = session.toSnapshot(
            status = status,
            completedAt = completedAt,
            success = success,
            outcomeMessage = sanitizedOutcome
        )
        writeSnapshot(snapshot)
        upsertHistory(
            TaskHistoryEntry(
                sessionId = session.sessionId,
                taskId = session.taskId,
                taskGoal = session.taskGoal,
                mode = session.mode,
                modelProvider = session.modelProvider,
                modelDisplayName = session.modelDisplayName,
                modelName = session.modelName,
                modelBaseUrl = session.modelBaseUrl,
                startedAt = session.startedAt,
                completedAt = completedAt,
                status = status,
                success = success,
                outcomeMessage = sanitizedOutcome,
                totalSteps = session.steps.size,
                failureType = failureType
            )
        )
    }

    override fun loadRecentHistory(limit: Int): List<TaskHistoryEntry> {
        return readHistory()
            .sortedByDescending { it.startedAt }
            .take(limit)
    }

    override fun loadSession(sessionId: String): SessionTrace? {
        return runCatching {
            findSessionFile(sessionId)?.let { file ->
                gson.fromJson(file.readText(), SessionTrace::class.java)
            }
        }.onFailure { error ->
            logError("读取 Session Trace 失败", error)
        }.getOrNull()
    }

    private fun writeSnapshot(snapshot: SessionTrace) {
        runCatching {
            val dateDir = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(snapshot.startedAt))
            val root = File(filesDir, "harness-traces/$dateDir")
            if (!root.exists()) {
                root.mkdirs()
            }
            val file = File(root, "session-${snapshot.sessionId}.json")
            writeAtomically(file, gson.toJson(snapshot))
            logDebug("Trace 已写入: ${file.absolutePath}")
        }.onFailure { error ->
            logError("写入 Trace 失败", error)
        }
    }

    private fun upsertHistory(entry: TaskHistoryEntry) {
        runCatching {
            val sanitizedEntry = TraceSanitizer.sanitizeHistoryEntry(entry)
            val history = readHistory()
                .filterNot { it.sessionId == sanitizedEntry.sessionId }
                .plus(sanitizedEntry)
                .map(TraceSanitizer::sanitizeHistoryEntry)
                .sortedByDescending { it.startedAt }
                .take(MAX_HISTORY_ENTRIES)
            writeAtomically(historyFile, gson.toJson(history))
            logDebug("任务历史已更新: ${sanitizedEntry.sessionId}")
        }.onFailure { error ->
            logError("写入任务历史失败", error)
        }
    }

    private fun readHistory(): List<TaskHistoryEntry> {
        if (!historyFile.exists()) {
            return emptyList()
        }

        return runCatching {
            val type = object : TypeToken<List<TaskHistoryEntry>>() {}.type
            gson.fromJson<List<TaskHistoryEntry>>(historyFile.readText(), type) ?: emptyList()
        }.onFailure { error ->
            logError("读取任务历史失败", error)
        }.getOrDefault(emptyList())
    }

    private fun findSessionFile(sessionId: String): File? {
        val root = File(filesDir, "harness-traces")
        if (!root.exists()) {
            return null
        }
        return root.walkTopDown()
            .firstOrNull { it.isFile && it.name == "session-$sessionId.json" }
    }

    private fun writeAtomically(file: File, content: String) {
        file.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                error("无法创建目录: ${parent.absolutePath}")
            }
        }
        val tempFile = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(tempFile).use { output ->
                OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
                    writer.write(content)
                    writer.flush()
                    output.fd.sync()
                }
            }
            try {
                Files.move(
                    tempFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tempFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun logDebug(message: String) {
        runCatching { Log.d(tag, message) }
    }

    private fun logError(message: String, error: Throwable) {
        runCatching { Log.e(tag, message, error) }
    }

    private data class MutableSessionTrace(
        val sessionId: String,
        val taskId: String,
        val taskGoal: String,
        val mode: String,
        val modelProvider: String?,
        val modelDisplayName: String?,
        val modelName: String?,
        val modelBaseUrl: String?,
        val startedAt: Long,
        val steps: MutableList<StepTrace> = mutableListOf()
    ) {
        fun toSnapshot(
            status: TaskHistoryStatus,
            completedAt: Long? = null,
            success: Boolean? = null,
            outcomeMessage: String? = null
        ): SessionTrace {
            return SessionTrace(
                sessionId = sessionId,
                taskId = taskId,
                taskGoal = taskGoal,
                mode = mode,
                modelProvider = modelProvider,
                modelDisplayName = modelDisplayName,
                modelName = modelName,
                modelBaseUrl = modelBaseUrl,
                startedAt = startedAt,
                completedAt = completedAt,
                success = success,
                outcomeMessage = outcomeMessage,
                totalSteps = steps.size,
                steps = steps.toList(),
                status = status,
                dataPolicy = TraceSanitizer.DATA_POLICY
            )
        }
    }

    private companion object {
        const val MAX_HISTORY_ENTRIES = 200
    }
}
