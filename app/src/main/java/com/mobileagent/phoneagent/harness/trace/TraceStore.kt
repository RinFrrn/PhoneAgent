package com.mobileagent.phoneagent.harness.trace

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

interface TraceStore {
    fun openSession(taskId: String, taskGoal: String, mode: String): String
    fun appendStep(sessionId: String, stepTrace: StepTrace)
    fun closeSession(sessionId: String, success: Boolean, outcomeMessage: String)
}

class FileTraceStore(
    private val context: Context
) : TraceStore {
    private val tag = "FileTraceStore"
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val sessions = linkedMapOf<String, MutableSessionTrace>()

    override fun openSession(taskId: String, taskGoal: String, mode: String): String {
        val sessionId = UUID.randomUUID().toString()
        sessions[sessionId] = MutableSessionTrace(
            sessionId = sessionId,
            taskId = taskId,
            taskGoal = taskGoal,
            mode = mode,
            startedAt = System.currentTimeMillis()
        )
        return sessionId
    }

    override fun appendStep(sessionId: String, stepTrace: StepTrace) {
        val session = sessions[sessionId] ?: return
        session.steps.add(stepTrace)
    }

    override fun closeSession(sessionId: String, success: Boolean, outcomeMessage: String) {
        val session = sessions.remove(sessionId) ?: return
        val snapshot = SessionTrace(
            sessionId = session.sessionId,
            taskId = session.taskId,
            taskGoal = session.taskGoal,
            mode = session.mode,
            startedAt = session.startedAt,
            completedAt = System.currentTimeMillis(),
            success = success,
            outcomeMessage = outcomeMessage,
            totalSteps = session.steps.size,
            steps = session.steps.toList()
        )
        writeSnapshot(snapshot)
    }

    private fun writeSnapshot(snapshot: SessionTrace) {
        runCatching {
            val dateDir = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(snapshot.startedAt))
            val root = File(context.filesDir, "harness-traces/$dateDir")
            if (!root.exists()) {
                root.mkdirs()
            }
            val file = File(root, "session-${snapshot.sessionId}.json")
            file.writeText(gson.toJson(snapshot))
            Log.d(tag, "Trace 已写入: ${file.absolutePath}")
        }.onFailure { error ->
            Log.e(tag, "写入 Trace 失败", error)
        }
    }

    private data class MutableSessionTrace(
        val sessionId: String,
        val taskId: String,
        val taskGoal: String,
        val mode: String,
        val startedAt: Long,
        val steps: MutableList<StepTrace> = mutableListOf()
    )
}
