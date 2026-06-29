package com.mobileagent.phoneagent.harness.learn

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.runtime.StepStatus
import com.mobileagent.phoneagent.harness.trace.SessionTrace
import com.mobileagent.phoneagent.harness.trace.StepTrace
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
                val anchors = buildSemanticAnchors(step, parsed)
                val signals = buildVerificationSignals(step, parsed)
                LearnedSkillStep(
                    stepIndex = step.stepIndex,
                    actionType = parsed.actionType,
                    targetHint = parsed.targetHint,
                    actionSummary = parsed.summary,
                    successSignal = buildSuccessSignal(signals)
                        ?: step.verification?.observedChange?.takeIf { it.isNotBlank() }
                        ?: step.execution?.message?.takeIf { it.isNotBlank() }
                        ?: "操作验证通过",
                    verificationReason = step.verification?.reason.orEmpty().ifBlank { "验证通过" },
                    semanticAnchors = anchors,
                    verificationSignals = signals,
                    recoveryHints = buildRecoveryHints(parsed, signals)
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
        val message = json.optString("message")
        val coordinate = when (actionType) {
            "Tap", "Click", "Long Press", "Double Tap" -> formatPoint(json.optArray("element"))
            else -> null
        }
        val inputText = when (actionType) {
            "Type", "Type_Name" -> sanitizeText(json.optString("text"))
            else -> ""
        }
        val appName = if (actionType == "Launch") json.optString("app") else ""
        val targetHint = when (actionType) {
            "Tap", "Click", "Long Press", "Double Tap" -> coordinate ?: "当前元素"
            "Swipe" -> "${formatPoint(json.optArray("start"))} -> ${formatPoint(json.optArray("end"))}"
            "Type", "Type_Name" -> inputText.ifBlank { "输入文本" }
            "Launch" -> appName.ifBlank { "启动应用" }
            "Take_over", "Note" -> message.ifBlank { "提示信息" }
            "Call_API" -> json.optString("instruction").ifBlank { "调用 API" }
            else -> message.ifBlank { "当前页面" }
        }

        return ParsedAction(
            actionType = actionType,
            targetHint = targetHint,
            summary = buildActionSummary(actionType, targetHint),
            appName = appName,
            inputText = inputText,
            message = message,
            coordinate = coordinate
        )
    }

    private fun buildSemanticAnchors(step: StepTrace, parsed: ParsedAction): List<SemanticAnchor> {
        val before = step.observationBefore
        return buildList {
            before.currentApp?.trim()?.takeIf { it.isNotBlank() }?.let {
                add(SemanticAnchor(SemanticAnchorType.CURRENT_APP, it, 0.9f))
            }
            before.currentPackage?.trim()?.takeIf { it.isNotBlank() }?.let {
                add(SemanticAnchor(SemanticAnchorType.CURRENT_PACKAGE, it, 0.95f))
            }
            parsed.appName.takeIf { it.isNotBlank() }?.let {
                add(SemanticAnchor(SemanticAnchorType.TARGET_TEXT, it, 0.9f))
            }
            parsed.inputText.takeIf { it.isNotBlank() }?.let {
                add(SemanticAnchor(SemanticAnchorType.INPUT_TEXT, it, 0.9f))
            }
            parsed.message.takeIf { it.isNotBlank() }?.let {
                add(SemanticAnchor(SemanticAnchorType.ACTION_MESSAGE, sanitizeText(it), 0.65f))
            }
            parsed.coordinate?.let {
                add(SemanticAnchor(SemanticAnchorType.COORDINATE, it, 0.35f))
            }

            visibleTexts(before)
                .filter { text -> text != parsed.inputText }
                .take(MAX_SCREEN_TEXT_ANCHORS)
                .forEach { text ->
                    add(SemanticAnchor(SemanticAnchorType.SCREEN_TEXT, text, 0.45f))
                }
        }.distinctBy { it.type to it.value }
    }

    private fun buildVerificationSignals(step: StepTrace, parsed: ParsedAction): List<VerificationSignal> {
        val before = step.observationBefore
        val after = step.observationAfter
        return buildList {
            if (step.execution?.shouldFinish == true || parsed.actionType == "finish") {
                add(
                    VerificationSignal(
                        VerificationSignalType.EXPLICIT_FINISH,
                        step.verification?.reason.orEmpty().ifBlank { "任务显式结束" },
                        "模型已声明任务完成"
                    )
                )
            }

            val launchTrace = step.execution?.launchTrace
            launchTrace?.targetPackage?.takeIf { it.isNotBlank() }?.let { targetPackage ->
                val observedPackage = after?.currentPackage ?: launchTrace.afterPackage
                if (observedPackage == targetPackage) {
                    add(
                        VerificationSignal(
                            VerificationSignalType.PACKAGE_REACHED,
                            targetPackage,
                            "启动后到达目标包名"
                        )
                    )
                }
            }

            if (after != null && before.currentPackage != after.currentPackage) {
                add(
                    VerificationSignal(
                        VerificationSignalType.PACKAGE_CHANGED,
                        "${before.currentPackage.orEmpty()} -> ${after.currentPackage.orEmpty()}",
                        "执行后包名变化"
                    )
                )
            }
            if (after != null && before.currentApp != after.currentApp) {
                add(
                    VerificationSignal(
                        VerificationSignalType.APP_CHANGED,
                        "${before.currentApp.orEmpty()} -> ${after.currentApp.orEmpty()}",
                        "执行后当前应用变化"
                    )
                )
            }

            if (after != null) {
                val beforeTexts = visibleTexts(before).toSet()
                val afterTexts = visibleTexts(after).toSet()
                (afterTexts - beforeTexts).take(MAX_TEXT_SIGNAL_COUNT).forEach { text ->
                    add(
                        VerificationSignal(
                            VerificationSignalType.VISIBLE_TEXT_APPEARED,
                            text,
                            "执行后出现新文本"
                        )
                    )
                }
                (beforeTexts - afterTexts).take(MAX_TEXT_SIGNAL_COUNT).forEach { text ->
                    add(
                        VerificationSignal(
                            VerificationSignalType.VISIBLE_TEXT_REMOVED,
                            text,
                            "执行后文本消失"
                        )
                    )
                }
                parsed.inputText.takeIf { it.isNotBlank() && after.textDigest().contains(it) }?.let { text ->
                    add(
                        VerificationSignal(
                            VerificationSignalType.INPUT_TEXT_VISIBLE,
                            text,
                            "输入内容在页面中可见"
                        )
                    )
                }
            }

            step.verification?.observedChange?.takeIf { it.isNotBlank() }?.let { change ->
                add(
                    VerificationSignal(
                        VerificationSignalType.CONTENT_CHANGED,
                        sanitizeText(change),
                        "验证器观察到页面变化"
                    )
                )
            }
            if (isEmpty()) {
                add(
                    VerificationSignal(
                        VerificationSignalType.GENERIC_VERIFICATION,
                        step.verification?.reason.orEmpty().ifBlank { "验证通过" },
                        "通用验证通过"
                    )
                )
            }
        }.distinctBy { it.type to it.value }
    }

    private fun buildSuccessSignal(signals: List<VerificationSignal>): String? {
        if (signals.isEmpty()) {
            return null
        }
        return signals
            .take(MAX_SIGNAL_SUMMARY_COUNT)
            .joinToString("；") { "${it.description}:${it.value}" }
            .take(MAX_TEXT_LENGTH)
    }

    private fun buildRecoveryHints(
        parsed: ParsedAction,
        signals: List<VerificationSignal>
    ): List<String> {
        return buildList {
            when (parsed.actionType) {
                "Tap", "Click", "Long Press", "Double Tap" -> {
                    add("复用时优先按文本/页面语义重新定位目标，坐标只作为低置信兜底。")
                    add("目标缺失或页面未变化时先重新观察、等待加载，避免连续点击同一坐标。")
                }
                "Type", "Type_Name" -> {
                    add("输入前确认焦点仍在目标输入框；输入后检查输入内容是否出现在页面。")
                }
                "Launch" -> {
                    add("启动后用包名或应用名确认已到达目标应用，未到达时不要重复后台启动。")
                }
                "Swipe" -> {
                    add("滑动后需要确认页面文本发生变化；无变化时换方向或缩短滑动距离。")
                }
            }
            if (signals.any { it.type == VerificationSignalType.CONTENT_CHANGED }) {
                add("如果出现广告或弹窗，先关闭遮挡物再继续当前路径。")
            }
        }.distinct().take(MAX_RECOVERY_HINTS)
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

    private fun visibleTexts(observation: Observation): List<String> {
        return observation.contentItems
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .map { sanitizeText(it) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun Observation.textDigest(): String {
        return visibleTexts(this).joinToString("\n")
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
        val summary: String,
        val appName: String = "",
        val inputText: String = "",
        val message: String = "",
        val coordinate: String? = null
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
        const val MAX_SCREEN_TEXT_ANCHORS = 5
        const val MAX_TEXT_SIGNAL_COUNT = 3
        const val MAX_SIGNAL_SUMMARY_COUNT = 2
        const val MAX_RECOVERY_HINTS = 3
    }
}
