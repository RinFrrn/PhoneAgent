package com.mobileagent.phoneagent.harness.learn

import android.content.Context
import com.mobileagent.phoneagent.model.ContentItem
import com.mobileagent.phoneagent.service.PhoneAgentAccessibilityService
import com.mobileagent.phoneagent.skill.SkillRegistry
import java.util.Locale
import java.util.UUID

object TeachingRecorder {
    private const val MAX_TEXT_LENGTH = 60
    private const val MAX_SCREEN_TEXT_ANCHORS = 5
    private const val MAX_TEXT_SIGNAL_COUNT = 3
    private const val MAX_TASK_KEYWORDS = 8
    private const val MAX_EVENT_BUFFER = 12
    private const val MAX_EVENT_ANCHORS = 4

    private var activeSession: TeachingSession? = null

    @Synchronized
    fun start(goal: String) {
        activeSession = TeachingSession(
            id = "teaching-${UUID.randomUUID()}",
            goal = normalizeGoal(goal),
            startedAt = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun updateGoal(goal: String) {
        val session = activeSession ?: return
        val normalized = goal.trim()
        if (normalized.isNotBlank()) {
            session.goal = normalized
        }
    }

    @Synchronized
    fun isActive(): Boolean = activeSession != null

    @Synchronized
    fun currentStepCount(): Int = activeSession?.steps?.size ?: 0

    @Synchronized
    internal fun pendingEventCountForTest(): Int = activeSession?.pendingEvents?.size ?: 0

    @Synchronized
    fun recordAccessibilityEvent(event: TeachingAccessibilityEvent) {
        val session = activeSession ?: return
        if (event.packageName == "com.mobileagent.phoneagent") {
            return
        }
        if (!event.isUsefulForTeaching()) {
            return
        }
        session.pendingEvents.add(event)
        while (session.pendingEvents.size > MAX_EVENT_BUFFER) {
            session.pendingEvents.removeAt(0)
        }
    }

    @Synchronized
    fun recordStep(goal: String, note: String): TeachingRecordResult {
        if (activeSession == null) {
            start(goal)
        }
        val session = activeSession ?: return TeachingRecordResult(false, "示教会话未启动", 0)
        updateGoal(goal)
        val service = PhoneAgentAccessibilityService.getInstance()
            ?: return TeachingRecordResult(false, "请先开启无障碍服务", session.steps.size)

        val observation = com.mobileagent.phoneagent.harness.observe.Observation(
            currentApp = service.getCurrentAppName(),
            currentPackage = service.getCurrentPackageName(),
            contentItems = listOf(
                ContentItem(
                    type = "text",
                    text = service.getScreenContent()
                )
            )
        )
        return recordStepFromObservation(goal, note, observation, System.currentTimeMillis())
    }

    @Synchronized
    internal fun recordStepFromObservationForTest(
        goal: String,
        note: String,
        observation: com.mobileagent.phoneagent.harness.observe.Observation,
        recordedAt: Long = System.currentTimeMillis()
    ): TeachingRecordResult {
        if (activeSession == null) {
            start(goal)
        }
        return recordStepFromObservation(goal, note, observation, recordedAt)
    }

    @Synchronized
    fun finish(context: Context): TeachingFinishResult {
        val session = activeSession ?: return TeachingFinishResult(false, "没有正在进行的示教录制", null)
        if (session.steps.size < 2) {
            return TeachingFinishResult(false, "至少记录 2 步，才能形成可复用路径", null)
        }

        val skill = buildSkill(session, System.currentTimeMillis())

        val saved = LearnedSkillRepository(context).saveFromTrace(skill)
        if (saved) {
            SkillRegistry.invalidateCache()
            activeSession = null
            return TeachingFinishResult(true, "已生成示教动态技能：${skill.displayName}", skill)
        }
        activeSession = null
        return TeachingFinishResult(false, "这个示教会话已经生成过技能", null)
    }

    @Synchronized
    internal fun buildSkillSnapshotForTest(now: Long = System.currentTimeMillis()): LearnedSkill? {
        val session = activeSession ?: return null
        if (session.steps.size < 2) {
            return null
        }
        return buildSkill(session, now)
    }

    @Synchronized
    fun cancel() {
        activeSession = null
    }

    private fun buildLearnedStep(previous: TeachingStep?, step: TeachingStep): LearnedSkillStep {
        val anchors = buildSemanticAnchors(step)
        val signals = buildVerificationSignals(previous, step)
        return LearnedSkillStep(
            stepIndex = step.index,
            actionType = "Teach",
            targetHint = step.note,
            actionSummary = "人工示教：${step.note}",
            successSignal = signals
                .take(2)
                .joinToString("；") { "${it.description}:${it.value}" }
                .ifBlank { "已记录页面状态" }
                .take(MAX_TEXT_LENGTH),
            verificationReason = if (previous == null) {
                "示教起点已记录"
            } else {
                "示教步骤前后页面证据已记录"
            },
            semanticAnchors = anchors,
            verificationSignals = signals,
            recoveryHints = listOf(
                "复用时优先根据步骤说明和页面文本重新定位目标。",
                "页面不一致、加载未完成或出现弹窗时先等待/关闭遮挡物，再继续示教路径。",
                "坐标不可作为唯一依据，必要时交给模型重新规划。"
            )
        )
    }

    private fun buildSkill(session: TeachingSession, now: Long): LearnedSkill {
        return LearnedSkill(
            id = "taught-${session.id.lowercase(Locale.US).filter { it.isLetterOrDigit() }.takeLast(12)}",
            displayName = "示教：${sanitizeText(session.goal).take(18)}",
            sourceTraceSessionId = session.id,
            sourceTaskGoal = session.goal,
            appKeywords = buildAppKeywords(session),
            packageNames = session.steps
                .mapNotNull { it.observation.currentPackage?.trim()?.takeIf(String::isNotBlank) }
                .distinct(),
            summary = buildSummary(session),
            caution = "该技能来自人工示教快照，复用时应按语义锚点重新定位，不要把它当作坐标回放。",
            steps = session.steps.mapIndexed { index, step ->
                buildLearnedStep(session.steps.getOrNull(index - 1), step)
            },
            status = LearnedSkillStatus.DRAFT,
            createdAt = now,
            updatedAt = now
        )
    }

    private fun recordStepFromObservation(
        goal: String,
        note: String,
        observation: com.mobileagent.phoneagent.harness.observe.Observation,
        recordedAt: Long
    ): TeachingRecordResult {
        val session = activeSession ?: return TeachingRecordResult(false, "示教会话未启动", 0)
        updateGoal(goal)
        val events = session.pendingEvents.toList()
        session.pendingEvents.clear()
        val stepNote = note.trim().ifBlank {
            buildAutoNote(events) ?: "记录当前页面状态"
        }
        val step = TeachingStep(
            index = session.steps.size + 1,
            note = stepNote,
            observation = observation,
            events = events,
            recordedAt = recordedAt
        )
        session.steps.add(step)
        return TeachingRecordResult(
            success = true,
            message = "已记录第 ${step.index} 步：${step.note}",
            stepCount = session.steps.size
        )
    }

    private fun buildSemanticAnchors(step: TeachingStep): List<SemanticAnchor> {
        return buildList {
            step.observation.currentApp?.trim()?.takeIf(String::isNotBlank)?.let {
                add(SemanticAnchor(SemanticAnchorType.CURRENT_APP, it, 0.9f))
            }
            step.observation.currentPackage?.trim()?.takeIf(String::isNotBlank)?.let {
                add(SemanticAnchor(SemanticAnchorType.CURRENT_PACKAGE, it, 0.95f))
            }
            add(SemanticAnchor(SemanticAnchorType.ACTION_MESSAGE, sanitizeText(step.note), 0.75f))
            step.events
                .mapNotNull { it.summary() }
                .take(MAX_EVENT_ANCHORS)
                .forEach { summary ->
                    add(SemanticAnchor(SemanticAnchorType.ACCESSIBILITY_EVENT, summary, 0.7f))
                }
            visibleTexts(step.observation)
                .take(MAX_SCREEN_TEXT_ANCHORS)
                .forEach { text ->
                    add(SemanticAnchor(SemanticAnchorType.SCREEN_TEXT, text, 0.5f))
                }
        }.distinctBy { it.type to it.value }
    }

    private fun buildVerificationSignals(previous: TeachingStep?, step: TeachingStep): List<VerificationSignal> {
        if (previous == null) {
            val eventSignals = step.events
                .mapNotNull { it.summary() }
                .take(MAX_EVENT_ANCHORS)
                .map { summary ->
                    VerificationSignal(
                        VerificationSignalType.GENERIC_VERIFICATION,
                        summary,
                        "示教操作事件"
                    )
                }
            return eventSignals.ifEmpty {
                listOf(
                    VerificationSignal(
                        VerificationSignalType.GENERIC_VERIFICATION,
                        sanitizeText(step.note),
                        "示教起点"
                    )
                )
            }
        }

        return buildList {
            step.events
                .mapNotNull { it.summary() }
                .take(MAX_EVENT_ANCHORS)
                .forEach { summary ->
                    add(
                        VerificationSignal(
                            VerificationSignalType.GENERIC_VERIFICATION,
                            summary,
                            "示教操作事件"
                        )
                    )
                }
            if (previous.observation.currentPackage != step.observation.currentPackage) {
                add(
                    VerificationSignal(
                        VerificationSignalType.PACKAGE_CHANGED,
                        "${previous.observation.currentPackage.orEmpty()} -> ${step.observation.currentPackage.orEmpty()}",
                        "包名变化"
                    )
                )
            }
            if (previous.observation.currentApp != step.observation.currentApp) {
                add(
                    VerificationSignal(
                        VerificationSignalType.APP_CHANGED,
                        "${previous.observation.currentApp.orEmpty()} -> ${step.observation.currentApp.orEmpty()}",
                        "应用变化"
                    )
                )
            }

            val previousTexts = visibleTexts(previous.observation).toSet()
            val currentTexts = visibleTexts(step.observation).toSet()
            (currentTexts - previousTexts).take(MAX_TEXT_SIGNAL_COUNT).forEach { text ->
                add(
                    VerificationSignal(
                        VerificationSignalType.VISIBLE_TEXT_APPEARED,
                        text,
                        "出现文本"
                    )
                )
            }
            (previousTexts - currentTexts).take(MAX_TEXT_SIGNAL_COUNT).forEach { text ->
                add(
                    VerificationSignal(
                        VerificationSignalType.VISIBLE_TEXT_REMOVED,
                        text,
                        "消失文本"
                    )
                )
            }
            if (isEmpty()) {
                add(
                    VerificationSignal(
                        VerificationSignalType.GENERIC_VERIFICATION,
                        sanitizeText(step.note),
                        "页面状态已记录"
                    )
                )
            }
        }.distinctBy { it.type to it.value }
    }

    private fun buildAppKeywords(session: TeachingSession): List<String> {
        return (
            session.steps.mapNotNull { it.observation.currentApp } +
                session.steps.mapNotNull { it.observation.currentPackage } +
                extractTaskKeywords(session.goal)
            )
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun buildSummary(session: TeachingSession): String {
        val actions = session.steps.joinToString(" -> ") { sanitizeText(it.note).ifBlank { "页面状态" } }
        return "完成“${sanitizeText(session.goal)}”的人工示教路径：${actions.take(80)}"
    }

    internal fun buildAutoNoteForTest(events: List<TeachingAccessibilityEvent>): String? {
        return buildAutoNote(events)
    }

    private fun buildAutoNote(events: List<TeachingAccessibilityEvent>): String? {
        return events
            .sortedWith(
                compareByDescending<TeachingAccessibilityEvent> { it.notePriority() }
                    .thenByDescending { it.eventTime }
            )
            .firstNotNullOfOrNull { event ->
                event.summary()?.let { "用户${it}" }
            }
    }

    private fun visibleTexts(observation: com.mobileagent.phoneagent.harness.observe.Observation): List<String> {
        return observation.contentItems
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .flatMap { text -> text.split('\n') }
            .map { sanitizeText(it) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun sanitizeText(text: String): String {
        return text.replace("\\s+".toRegex(), " ").trim().take(MAX_TEXT_LENGTH)
    }

    private fun normalizeGoal(goal: String): String {
        return goal.trim().ifBlank { "未命名示教任务" }
    }

    private fun extractTaskKeywords(taskGoal: String): List<String> {
        return taskGoal
            .lowercase(Locale.getDefault())
            .split(Regex("\\s+|，|。|、|,|\\.|:|：|;|；"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .take(MAX_TASK_KEYWORDS)
    }

    private data class TeachingSession(
        val id: String,
        var goal: String,
        val startedAt: Long,
        val steps: MutableList<TeachingStep> = mutableListOf(),
        val pendingEvents: MutableList<TeachingAccessibilityEvent> = mutableListOf()
    )

    private data class TeachingStep(
        val index: Int,
        val note: String,
        val observation: com.mobileagent.phoneagent.harness.observe.Observation,
        val events: List<TeachingAccessibilityEvent>,
        val recordedAt: Long
    )
}

data class TeachingAccessibilityEvent(
    val eventType: String,
    val packageName: String?,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val eventTime: Long
) {
    fun isUsefulForTeaching(): Boolean {
        return eventType in setOf(
            "CLICK",
            "LONG_CLICK",
            "TEXT_CHANGED",
            "TEXT_SELECTION",
            "VIEW_FOCUSED",
            "WINDOW_CHANGED"
        ) && summary() != null
    }

    fun summary(): String? {
        val label = listOf(text, contentDescription, className)
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?.replace("\\s+".toRegex(), " ")
            ?.take(60)
            ?: return null
        return when (eventType) {
            "CLICK" -> "点击 $label"
            "LONG_CLICK" -> "长按 $label"
            "TEXT_CHANGED" -> "输入 $label"
            "TEXT_SELECTION" -> "选择文本 $label"
            "VIEW_FOCUSED" -> "聚焦 $label"
            "WINDOW_CHANGED" -> "页面变化 $label"
            else -> "$eventType $label"
        }
    }

    fun notePriority(): Int {
        return when (eventType) {
            "TEXT_CHANGED" -> 100
            "CLICK", "LONG_CLICK" -> 90
            "TEXT_SELECTION" -> 70
            "VIEW_FOCUSED" -> 40
            "WINDOW_CHANGED" -> 10
            else -> 0
        }
    }
}

data class TeachingRecordResult(
    val success: Boolean,
    val message: String,
    val stepCount: Int
)

data class TeachingFinishResult(
    val success: Boolean,
    val message: String,
    val skill: LearnedSkill?
)
