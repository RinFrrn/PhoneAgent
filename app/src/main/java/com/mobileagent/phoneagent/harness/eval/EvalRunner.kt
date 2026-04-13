package com.mobileagent.phoneagent.harness.eval

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mobileagent.phoneagent.harness.spec.TaskSpec
import com.mobileagent.phoneagent.harness.trace.SessionTrace
import java.io.File

class EvalRunner(
    private val context: Context
) {
    private val tag = "EvalRunner"
    private val gson = Gson()

    fun loadDefaultCases(assetName: String = "eval_cases.json"): List<EvalCase> {
        return runCatching {
            val json = context.assets.open(assetName).bufferedReader().use { it.readText() }
            val type = object : TypeToken<List<EvalCase>>() {}.type
            gson.fromJson<List<EvalCase>>(json, type) ?: emptyList()
        }.getOrElse {
            Log.w(tag, "加载评测用例失败: ${it.message}")
            emptyList()
        }
    }

    fun evaluateRecentSessions(
        cases: List<EvalCase>,
        limit: Int = 100
    ): EvalReport {
        val sessions = loadRecentSessions(limit)
        val results = cases.map { evaluateCase(it, sessions) }
        val passedCases = results.count { it.passed }
        return EvalReport(
            generatedAt = System.currentTimeMillis(),
            totalCases = results.size,
            passedCases = passedCases,
            failedCases = results.size - passedCases,
            results = results
        )
    }

    fun loadRecentSessions(limit: Int = 100): List<SessionTrace> {
        val root = File(context.filesDir, "harness-traces")
        if (!root.exists()) {
            return emptyList()
        }

        return root.walkTopDown()
            .filter { it.isFile && it.extension == "json" && it.name.startsWith("session-") }
            .sortedByDescending { it.lastModified() }
            .take(limit)
            .mapNotNull(::readSessionTrace)
            .toList()
    }

    fun loadSessionById(sessionId: String): SessionTrace? {
        val root = File(context.filesDir, "harness-traces")
        if (!root.exists()) {
            return null
        }

        return root.walkTopDown()
            .firstOrNull { it.isFile && it.name == "session-$sessionId.json" }
            ?.let(::readSessionTrace)
    }

    fun evaluateSession(
        evalCase: EvalCase,
        session: SessionTrace
    ): EvalCaseResult {
        return evaluateMatchedCase(evalCase, session)
    }

    fun buildTaskSpec(evalCase: EvalCase): TaskSpec? {
        val taskGoal = evalCase.taskGoal ?: return null
        return TaskSpec(
            id = "eval-${evalCase.id}",
            goal = taskGoal,
            mode = evalCase.taskMode ?: evalCase.mode ?: "HYBRID",
            maxSteps = evalCase.taskMaxSteps ?: evalCase.maxSteps ?: 20
        )
    }

    private fun readSessionTrace(file: File): SessionTrace? {
        return runCatching {
            gson.fromJson(file.readText(), SessionTrace::class.java)
        }.onFailure {
            Log.w(tag, "读取 Trace 失败: ${file.absolutePath}")
        }.getOrNull()
    }

    private fun evaluateCase(
        evalCase: EvalCase,
        sessions: List<SessionTrace>
    ): EvalCaseResult {
        val matchedSession = sessions.firstOrNull { session -> matches(evalCase, session) }
            ?: return EvalCaseResult(
                caseId = evalCase.id,
                caseName = evalCase.name,
                matchedSessionId = null,
                passed = false,
                reasons = listOf("未找到匹配的 session trace")
            )

        return evaluateMatchedCase(evalCase, matchedSession)
    }

    private fun evaluateMatchedCase(
        evalCase: EvalCase,
        matchedSession: SessionTrace
    ): EvalCaseResult {
        val reasons = mutableListOf<String>()

        if (matchedSession.success != evalCase.expectSuccess) {
            reasons += "success 不匹配: expected=${evalCase.expectSuccess}, actual=${matchedSession.success}"
        }

        evalCase.maxSteps?.let { maxSteps ->
            if (matchedSession.totalSteps > maxSteps) {
                reasons += "步数超限: max=$maxSteps, actual=${matchedSession.totalSteps}"
            }
        }

        evalCase.outcomeContains?.let { outcomeContains ->
            val outcomeMessage = matchedSession.outcomeMessage.orEmpty()
            if (!outcomeMessage.contains(outcomeContains, ignoreCase = true)) {
                reasons += "结果消息未包含 '$outcomeContains'"
            }
        }

        evalCase.minVerificationPassRate?.let { minRate ->
            val verifications = matchedSession.steps.mapNotNull { it.verification }
            val passRate = if (verifications.isEmpty()) {
                0f
            } else {
                verifications.count { it.passed }.toFloat() / verifications.size.toFloat()
            }
            if (passRate < minRate) {
                reasons += "验证通过率不足: min=$minRate, actual=$passRate"
            }
        }

        if (reasons.isEmpty()) {
            reasons += "评测通过"
        }

        return EvalCaseResult(
            caseId = evalCase.id,
            caseName = evalCase.name,
            matchedSessionId = matchedSession.sessionId,
            passed = reasons.size == 1 && reasons.first() == "评测通过",
            reasons = reasons
        )
    }

    private fun matches(evalCase: EvalCase, session: SessionTrace): Boolean {
        if (evalCase.taskIdContains != null &&
            !session.taskId.contains(evalCase.taskIdContains, ignoreCase = true)
        ) {
            return false
        }

        if (evalCase.taskGoalContains != null &&
            !session.taskGoal.contains(evalCase.taskGoalContains, ignoreCase = true)
        ) {
            return false
        }

        if (evalCase.mode != null && !session.mode.equals(evalCase.mode, ignoreCase = true)) {
            return false
        }

        return true
    }
}
