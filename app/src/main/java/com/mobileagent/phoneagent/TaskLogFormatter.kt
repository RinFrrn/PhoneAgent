package com.mobileagent.phoneagent

import com.mobileagent.phoneagent.agent.StepResult
import com.mobileagent.phoneagent.harness.trace.ModelCallSummaryBuilder
import com.mobileagent.phoneagent.harness.trace.SessionTrace
import com.mobileagent.phoneagent.harness.trace.StepTrace
import com.mobileagent.phoneagent.harness.trace.TaskNoteSummaryBuilder
import com.mobileagent.phoneagent.utils.LogSanitizer
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TaskLogFormatter {
    private const val THINKING_PREVIEW_LENGTH = 420
    private const val RAW_RESPONSE_PREVIEW_LENGTH = 700
    private const val ACTION_PREVIEW_LENGTH = 700
    private const val MESSAGE_PREVIEW_LENGTH = 500

    fun formatLiveStep(step: StepResult): String {
        val purpose = step.purpose
            ?: summarizeStepPurpose(
                actionJson = step.action,
                thinking = step.thinking,
                executionMessage = step.message
            )
        return buildString {
            appendLine("──── 步骤 ${step.stepIndex ?: "?"} · ${step.status ?: formatStepStatus(step.success, step.finished)} ────")
            appendLine("本步目的: $purpose")
            step.currentApp?.takeIf { it.isNotBlank() }?.let { appendLine("当前应用: $it") }
            step.runtimeWarnings.forEach { warning ->
                appendLine("运行提示: $warning")
            }
            appendLogSection("模型思考", step.thinking, THINKING_PREVIEW_LENGTH)
            step.rawResponse?.let { appendLogSection("模型输出", it, RAW_RESPONSE_PREVIEW_LENGTH) }
            appendLogSection("提取动作", step.action, ACTION_PREVIEW_LENGTH)
            appendLine("动作说明: ${describeActionPurpose(step.action)}")
            step.message?.let { appendLogSection("执行结果", it, MESSAGE_PREVIEW_LENGTH) }
            step.taskNote?.let { appendLine("任务笔记: $it") }
            step.verificationSummary?.let { appendLine("验证结果: $it") }
            step.failureType?.let { appendLine("失败类型: $it") }
            appendLine()
        }.trimEnd().let(LogSanitizer::sanitize)
    }

    fun formatSession(session: SessionTrace): String {
        return buildString {
            appendLine("任务: ${session.taskGoal}")
            appendLine("模式: ${session.mode}")
            appendLine("模型: ${formatModel(session.modelDisplayName, session.modelProvider, session.modelName)}")
            appendLine("开始: ${formatTime(session.startedAt)}")
            session.completedAt?.let { appendLine("结束: ${formatTime(it)}") }
            appendLine("结果: ${formatOutcome(session.success, session.outcomeMessage)}")
            appendLine("步骤数: ${session.totalSteps}")
            appendLine(ModelCallSummaryBuilder.summarize(session).toDisplayText())
            appendLine(TaskNoteSummaryBuilder.summarize(session).toDisplayText())
            appendLine("Trace: ${session.sessionId}")
            appendLine()
            appendLine("========== 输出过程 ==========")
            if (session.steps.isEmpty()) {
                appendLine("暂无步骤日志")
            } else {
                session.steps.forEach { step ->
                    appendLine(formatTraceStep(step))
                    appendLine()
                }
            }
        }.trimEnd().let(LogSanitizer::sanitize)
    }

    fun formatTraceStep(step: StepTrace): String {
        val actionJson = step.decision?.actionJson.orEmpty()
        val purpose = summarizeStepPurpose(
            actionJson = actionJson,
            thinking = step.decision?.thinking,
            executionMessage = step.execution?.message
        )
        return buildString {
            appendLine("──── 步骤 ${step.stepIndex} · ${step.status} ────")
            appendLine("本步目的: $purpose")
            step.observationBefore.currentApp?.takeIf { it.isNotBlank() }?.let {
                appendLine("观察应用: $it")
            }
            step.observationBefore.currentPackage?.takeIf { it.isNotBlank() }?.let {
                appendLine("包名: $it")
            }
            step.runtimeWarnings.forEach { warning ->
                appendLine("运行提示: [${warning.severity}] ${warning.message}")
            }
            step.observationBefore.failureMessage?.takeIf { it.isNotBlank() }?.let {
                appendLine("观察异常: $it")
            }
            step.decision?.let { decision ->
                appendLine(
                    "规划来源: ${decision.source}" +
                        (decision.executor?.let { ", executor=$it" } ?: "") +
                        (decision.confidence?.let { ", confidence=$it" } ?: "")
                )
                appendLogSection("模型思考", decision.thinking, THINKING_PREVIEW_LENGTH)
                appendLogSection("模型输出", decision.rawResponse, RAW_RESPONSE_PREVIEW_LENGTH)
                decision.modelCallStats?.let { stats ->
                    appendLine("模型统计: ${stats.summary()}")
                }
                appendLogSection("提取动作", decision.actionJson, ACTION_PREVIEW_LENGTH)
                appendLine("动作说明: ${describeActionPurpose(decision.actionJson)}")
            } ?: appendLine("模型输出: 无")
            step.execution?.let { execution ->
                execution.message?.let { appendLogSection("执行结果", it, MESSAGE_PREVIEW_LENGTH) }
                execution.taskNote?.let { appendLine("任务笔记: ${it.toDisplayText()}") }
                appendLine("执行成功: ${if (execution.success) "是" else "否"}")
                appendLine("请求结束任务: ${if (execution.shouldFinish) "是" else "否"}")
                execution.failureType?.let { appendLine("失败类型: $it") }
                execution.launchTrace?.let { trace ->
                    appendLine("启动链路: app=${trace.targetAppName}, package=${trace.targetPackage ?: "未解析"}, status=${trace.status}")
                }
                execution.humanizationTrace?.let { trace ->
                    appendLine(
                        "执行人性化: enabled=${trace.enabled}, level=${trace.level}, delayMs=${trace.delayMs}, " +
                            "positionRandomized=${trace.positionRandomized}, reason=${trace.reason}"
                    )
                }
            } ?: appendLine("执行结果: 未执行")
            step.verification?.let { verification ->
                appendLine(
                    "验证结果: passed=${verification.passed}, confidence=${verification.confidence}, reason=${verification.reason}"
                )
                verification.observedChange?.takeIf { it.isNotBlank() }?.let {
                    appendLine("观察变化: $it")
                }
            }
            step.errorMessage?.let { appendLine("错误: $it") }
            step.failureType?.let { appendLine("失败类型: $it") }
        }.trimEnd().let(LogSanitizer::sanitize)
    }

    fun summarizeStepPurpose(
        actionJson: String,
        thinking: String? = null,
        executionMessage: String? = null
    ): String {
        val json = runCatching { JSONObject(actionJson) }.getOrNull()
            ?: return sentenceFromText(executionMessage)
                ?: sentenceFromText(thinking)
                ?: "解析下一步操作，等待获取可执行动作。"
        explicitPurpose(json)?.let { return it }
        if (json.optString("_metadata") == "finish") {
            return "结束任务并汇报结果。"
        }
        if (json.optString("_metadata") != "do") {
            return sentenceFromText(thinking) ?: "执行模型返回的动作，继续推进任务。"
        }
        return when (val action = json.optString("action")) {
            "Tap", "Click" -> summarizeTapPurpose(json)
            "Long Press" -> summarizePointPurpose("长按", json)
            "Double Tap" -> summarizePointPurpose("双击", json)
            "Swipe" -> summarizeSwipePurpose(json)
            "Type", "Type_Name" -> {
                val text = json.optString("text", "")
                if (text.isBlank()) {
                    "在当前输入框输入内容，推进任务。"
                } else {
                    "输入“${text.take(24)}”，完成当前输入框内容填写。"
                }
            }
            "Launch" -> {
                val app = json.optString("app", "").ifBlank { "目标应用" }
                "打开$app，进入任务所需应用环境。"
            }
            "Back" -> "返回上一层，离开当前无关页面或关闭弹窗。"
            "Home" -> "回到桌面，重置当前应用导航上下文。"
            "Wait" -> "等待页面加载或操作结果稳定。"
            "Take_over" -> "请求用户介入处理登录、验证或敏感操作。"
            "Note" -> "记录当前页面信息，供后续总结或判断使用。"
            "Call_API" -> {
                val instruction = json.optString("instruction", "")
                if (instruction.isBlank()) "调用外部能力处理当前信息。" else instruction.toPurposeSentence()
            }
            "Interact" -> "当前有多个候选项，请用户确认下一步选择。"
            else -> {
                val fallback = sentenceFromText(thinking)
                fallback ?: "执行 $action 动作，目标语义不明确，需结合页面上下文确认。"
            }
        }
    }

    fun describeActionPurpose(actionJson: String): String {
        val json = runCatching { JSONObject(actionJson) }.getOrNull()
            ?: return "无法解析动作 JSON；复用 skill 前需要人工确认动作语义。"
        return when (json.optString("_metadata")) {
            "finish" -> "结束任务并返回结果: ${json.optString("message", "未提供完成说明")}"
            "do" -> describeDoAction(json)
            else -> "未知动作元数据 ${json.optString("_metadata", "空")}；复用前需要确认动作类型。"
        }
    }

    private fun describeDoAction(json: JSONObject): String {
        return when (val action = json.optString("action")) {
            "Tap", "Click" -> describeTap(json)
            "Long Press" -> describePointAction("长按", json)
            "Double Tap" -> describePointAction("双击", json)
            "Swipe" -> describeSwipe(json)
            "Type", "Type_Name" -> {
                val text = json.optString("text", "")
                "输入文本: ${text.ifBlank { "空文本" }}；复用 skill 时需确认当前焦点已经在目标输入框。"
            }
            "Launch" -> "启动应用: ${json.optString("app", "未指定")}；复用 skill 时先确认应用名能正确匹配安装包。"
            "Back" -> "返回上一层页面；复用 skill 时需确认当前页面层级一致。"
            "Home" -> "回到系统桌面；通常用于重置上下文或结束当前应用路径。"
            "Wait" -> "等待 ${json.optString("duration", "默认时长")}；用于等待页面加载、动画或网络结果稳定。"
            "Take_over" -> "请求用户介入: ${json.optString("message", "未提供说明")}。"
            "Note" -> "记录中间状态: ${json.optString("message", "未提供说明")}。"
            "Call_API" -> "调用外部能力: ${json.optString("instruction", "未提供说明")}。"
            "Interact" -> "交互式处理当前页面；复用前需要结合页面上下文确认具体动作。"
            else -> "未知动作 $action；复用 skill 时需要人工补充动作目的。"
        }
    }

    private fun explicitPurpose(json: JSONObject): String? {
        return listOf("purpose", "message", "instruction")
            .firstNotNullOfOrNull { key ->
                json.optString(key, "")
                    .takeIf { it.isNotBlank() && it != "重要操作" && it != "True" }
                    ?.toPurposeSentence()
            }
    }

    private fun summarizeTapPurpose(json: JSONObject): String {
        val point = readPoint(json, "element")
        val target = json.optString("target", "").ifBlank {
            json.optString("text", "")
        }
        if (target.isNotBlank()) {
            return "点击$target，推进当前操作。"
        }
        return if (point != null) {
            "点击坐标 (${point.first}, ${point.second})，尝试打开目标控件。"
        } else {
            "点击当前目标控件，推进任务。"
        }
    }

    private fun summarizePointPurpose(label: String, json: JSONObject): String {
        val point = readPoint(json, "element")
        return if (point != null) {
            "$label 坐标 (${point.first}, ${point.second})，操作目标控件。"
        } else {
            "$label 目标控件，推进当前步骤。"
        }
    }

    private fun summarizeSwipePurpose(json: JSONObject): String {
        val start = readPoint(json, "start")
        val end = readPoint(json, "end")
        if (start == null || end == null) {
            return "滑动当前页面，继续查找目标内容。"
        }
        val dx = end.first - start.first
        val dy = end.second - start.second
        return when {
            kotlin.math.abs(dy) >= kotlin.math.abs(dx) && dy < 0 ->
                "向上滑动列表，继续查找目标内容。"
            kotlin.math.abs(dy) >= kotlin.math.abs(dx) ->
                "向下滑动列表，回看上方内容或调整位置。"
            dx < 0 -> "向左滑动页面，切换到后续内容。"
            else -> "向右滑动页面，切换到前序内容。"
        }
    }

    private fun describeTap(json: JSONObject): String {
        val point = readPoint(json, "element")
        val message = json.optString("message", "").ifBlank { "选择/触发目标控件" }
        return if (point != null) {
            "点击坐标: (${point.first}, ${point.second})，目的: $message；复用 skill 时需确认目标控件仍在同一语义位置，坐标只作为辅助线索。"
        } else {
            "点击目标未提供坐标，目的: $message；复用 skill 时需重新定位目标控件。"
        }
    }

    private fun describePointAction(label: String, json: JSONObject): String {
        val point = readPoint(json, "element")
        return if (point != null) {
            "$label 坐标: (${point.first}, ${point.second})；复用 skill 时需确认目标控件仍在同一语义位置。"
        } else {
            "$label 未提供坐标；复用 skill 时需重新定位目标控件。"
        }
    }

    private fun describeSwipe(json: JSONObject): String {
        val start = readPoint(json, "start")
        val end = readPoint(json, "end")
        return if (start != null && end != null) {
            "滑动路径: (${start.first}, ${start.second}) -> (${end.first}, ${end.second})；目的通常是滚动列表或切换页面，复用 skill 时需确认内容方向和可滚动区域。"
        } else {
            "滑动动作坐标不完整；复用 skill 时需确认滚动区域和方向。"
        }
    }

    private fun readPoint(json: JSONObject, key: String): Pair<Int, Int>? {
        val array = json.optJSONArray(key)
        if (array != null) {
            return readPoint(array)
        }
        val value = json.optString(key, "")
        if (value.isBlank()) {
            return null
        }
        val parts = value.split(",").map { it.trim().toIntOrNull() }
        val x = parts.getOrNull(0) ?: return null
        val y = parts.getOrNull(1) ?: return null
        return x to y
    }

    private fun readPoint(array: JSONArray): Pair<Int, Int>? {
        if (array.length() < 2) {
            return null
        }
        return array.optInt(0) to array.optInt(1)
    }

    private fun StringBuilder.appendLogSection(title: String, content: String, maxLength: Int) {
        val compact = compactText(content)
        val preview = if (compact.length > maxLength) {
            compact.take(maxLength).trimEnd() + "..."
        } else {
            compact
        }
        appendLine("$title: ${preview.ifBlank { "无" }}")
    }

    private fun compactText(text: String): String {
        return text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private fun sentenceFromText(text: String?): String? {
        val compact = text?.let(::compactText).orEmpty()
        if (compact.isBlank()) {
            return null
        }
        return compact
            .split('。', '！', '？', '.', '!', '?', '\n')
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(56)
            ?.toPurposeSentence()
    }

    private fun String.toPurposeSentence(): String {
        val trimmed = trim().trimEnd('。', '.', '；', ';')
        if (trimmed.isBlank()) {
            return "继续推进任务。"
        }
        return if (trimmed.endsWith("。")) trimmed else "$trimmed。"
    }

    private fun formatStepStatus(success: Boolean, finished: Boolean): String {
        return when {
            finished && success -> "任务完成"
            finished -> "已结束"
            success -> "成功，继续执行"
            else -> "失败"
        }
    }

    private fun formatModel(displayName: String?, provider: String?, modelName: String?): String {
        return listOf(displayName, provider, modelName)
            .mapNotNull { it?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString(" · ")
            .ifBlank { "未记录" }
    }

    private fun formatOutcome(success: Boolean?, message: String?): String {
        val state = when (success) {
            true -> "成功"
            false -> "失败"
            null -> "运行中或未完成"
        }
        return if (message.isNullOrBlank()) state else "$state · $message"
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }
}
