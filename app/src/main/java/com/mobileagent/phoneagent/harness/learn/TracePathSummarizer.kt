package com.mobileagent.phoneagent.harness.learn

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mobileagent.phoneagent.harness.runtime.StepStatus
import com.mobileagent.phoneagent.harness.trace.SessionTrace
import java.util.Locale
import java.util.UUID

class TracePathSummarizer {
    fun summarize(session: SessionTrace, now: Long = System.currentTimeMillis()): LearnedSkill? {
        if (session.success != true) {
            return null
        }

        val reusableSteps = session.steps
            .filter { step ->
                (step.status == StepStatus.EXECUTED || step.status == StepStatus.FINISHED) &&
                    step.verification?.passed == true &&
                    step.execution != null
            }
            .mapNotNull { step ->
                val actionJson = step.execution?.actionJson ?: step.decision?.actionJson ?: return@mapNotNull null
                val parsed = parseAction(actionJson)
                LearnedSkillStep(
                    stepIndex = step.stepIndex,
                    actionType = parsed.actionType,
                    targetHint = parsed.targetHint,
                    actionSummary = parsed.summary,
                    successSignal = step.verification?.observedChange?.takeIf { it.isNotBlank() }
                        ?: step.execution?.message?.takeIf { it.isNotBlank() }
                        ?: "操作验证通过",
                    verificationReason = step.verification?.reason.orEmpty().ifBlank { "验证通过" }
                )
            }

        if (reusableSteps.size < MIN_REUSABLE_STEPS) {
            return null
        }

        val appKeywords = session.steps
            .flatMap { listOfNotNull(it.observationBefore.currentApp, it.observationAfter?.currentApp) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val packageNames = session.steps
            .flatMap { listOfNotNull(it.observationBefore.currentPackage, it.observationAfter?.currentPackage) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val failedCount = session.steps.count { it.status == StepStatus.FAILED }
        val caution = if (failedCount > 0) {
            "原始 trace 中有 ${failedCount} 个失败步骤，复用时只参考已验证通过的路径。"
        } else {
            null
        }

        return LearnedSkill(
            id = buildSkillId(session.sessionId),
            displayName = buildDisplayName(session.taskGoal),
            sourceTraceSessionId = session.sessionId,
            sourceTaskGoal = session.taskGoal,
            appKeywords = (appKeywords + packageNames + extractTaskKeywords(session.taskGoal)).distinct(),
            packageNames = packageNames,
            summary = buildSummary(session.taskGoal, reusableSteps),
            caution = caution,
            steps = reusableSteps,
            status = LearnedSkillStatus.DRAFT,
            createdAt = now,
            updatedAt = now
        )
    }

    private fun parseAction(actionJson: String): ParsedAction {
        val json = runCatching { JsonParser.parseString(actionJson).asJsonObject }.getOrNull()
            ?: return ParsedAction("Unknown", "无法解析目标", "执行未知操作")

        val metadata = json.optString("_metadata")
        if (metadata == "finish") {
            val message = json.optString("message").ifBlank { "任务完成" }
            return ParsedAction("finish", message, "结束任务：$message")
        }

        val actionType = json.optString("action").ifBlank { metadata.ifBlank { "Unknown" } }
        val targetHint = when (actionType) {
            "Tap", "Click", "Long Press", "Double Tap" -> formatPoint(json.optArray("element"))
            "Swipe" -> "${formatPoint(json.optArray("start"))} -> ${formatPoint(json.optArray("end"))}"
            "Type", "Type_Name" -> sanitizeText(json.optString("text")).ifBlank { "输入文本" }
            "Launch" -> json.optString("app").ifBlank { "启动应用" }
            "Take_over", "Note" -> json.optString("message").ifBlank { "提示信息" }
            "Call_API" -> json.optString("instruction").ifBlank { "调用 API" }
            else -> json.optString("message").ifBlank { "当前页面" }
        }

        return ParsedAction(
            actionType = actionType,
            targetHint = targetHint,
            summary = buildActionSummary(actionType, targetHint)
        )
    }

    private fun buildActionSummary(actionType: String, targetHint: String): String {
        return when (actionType) {
            "Tap", "Click" -> "点击 $targetHint"
            "Long Press" -> "长按 $targetHint"
            "Double Tap" -> "双击 $targetHint"
            "Swipe" -> "滑动 $targetHint"
            "Type", "Type_Name" -> "输入 $targetHint"
            "Launch" -> "启动 $targetHint"
            "Back" -> "返回上一页"
            "Home" -> "回到桌面"
            "Wait" -> "等待页面变化"
            else -> "$actionType $targetHint"
        }
    }

    private fun formatPoint(array: JsonArray?): String {
        if (array == null) {
            return "当前元素"
        }
        return "坐标(${array.optInt(0)}, ${array.optInt(1)})"
    }

    private fun sanitizeText(text: String): String {
        return text.replace('\n', ' ').trim().take(MAX_TEXT_LENGTH)
    }

    private fun buildDisplayName(taskGoal: String): String {
        val compact = sanitizeText(taskGoal)
        return "路径：${compact.ifBlank { "未命名任务" }.take(DISPLAY_NAME_GOAL_LENGTH)}"
    }

    private fun buildSummary(taskGoal: String, steps: List<LearnedSkillStep>): String {
        val actionSummary = steps.joinToString(" → ") { it.actionType }.take(MAX_SUMMARY_ACTION_LENGTH)
        return "完成“${sanitizeText(taskGoal).take(SUMMARY_GOAL_LENGTH)}”的已验证路径：$actionSummary"
    }

    private fun buildSkillId(sessionId: String): String {
        val stablePart = sessionId.lowercase(Locale.US).filter { it.isLetterOrDigit() }.take(12)
        return "learned-${stablePart.ifBlank { UUID.randomUUID().toString().take(12) }}"
    }

    private fun extractTaskKeywords(taskGoal: String): List<String> {
        val normalized = taskGoal.lowercase(Locale.getDefault())
        return normalized
            .split(Regex("\\s+|，|。|、|,|\\.|:|：|;|；"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .take(MAX_TASK_KEYWORDS)
    }

    private data class ParsedAction(
        val actionType: String,
        val targetHint: String,
        val summary: String
    )

    private fun JsonObject.optString(key: String): String {
        return runCatching {
            if (has(key) && !get(key).isJsonNull) get(key).asString else ""
        }.getOrDefault("")
    }

    private fun JsonObject.optArray(key: String): JsonArray? {
        return runCatching {
            if (has(key) && get(key).isJsonArray) getAsJsonArray(key) else null
        }.getOrNull()
    }

    private fun JsonArray.optInt(index: Int): Int {
        return runCatching { get(index).asInt }.getOrDefault(0)
    }

    private companion object {
        const val MIN_REUSABLE_STEPS = 2
        const val DISPLAY_NAME_GOAL_LENGTH = 18
        const val SUMMARY_GOAL_LENGTH = 40
        const val MAX_TEXT_LENGTH = 60
        const val MAX_SUMMARY_ACTION_LENGTH = 80
        const val MAX_TASK_KEYWORDS = 8
    }
}
