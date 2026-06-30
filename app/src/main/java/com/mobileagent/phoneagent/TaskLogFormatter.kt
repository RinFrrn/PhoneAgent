package com.mobileagent.phoneagent

import com.mobileagent.phoneagent.agent.StepResult
import com.mobileagent.phoneagent.harness.trace.ModelCallHealthAnalyzer
import com.mobileagent.phoneagent.harness.trace.ModelCallSummaryBuilder
import com.mobileagent.phoneagent.harness.trace.SessionTrace
import com.mobileagent.phoneagent.harness.trace.StepTrace
import com.mobileagent.phoneagent.harness.trace.TaskNoteSummaryBuilder
import com.mobileagent.phoneagent.harness.trace.VisualContextSummaryBuilder
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
            val modelSummary = ModelCallSummaryBuilder.summarize(session)
            appendLine(modelSummary.toDisplayText())
            appendLine(ModelCallHealthAnalyzer.analyze(modelSummary).toDisplayText())
            appendLine(VisualContextSummaryBuilder.summarize(session).toDisplayText())
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
                execution.userInteractionRequest?.let { request ->
                    appendLine("用户协作: ${request.toDisplayText()}")
                }
                execution.clipboardTrace?.let { trace ->
                    appendLine("剪贴板: ${trace.toDisplayText()}")
                }
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
        if (json.optString("_metadata") != "do" && !json.has("action")) {
            return sentenceFromText(thinking) ?: "执行模型返回的动作，继续推进任务。"
        }
        return when (val action = json.optString("action")) {
            "Tap", "Click" -> summarizeTapPurpose(json)
            "tap", "click" -> summarizeTapPurpose(json)
            "Long Press" -> summarizePointPurpose("长按", json)
            "long_press", "longpress" -> summarizePointPurpose("长按", json)
            "Double Tap" -> summarizePointPurpose("双击", json)
            "double_tap", "doubletap" -> summarizePointPurpose("双击", json)
            "Swipe" -> summarizeSwipePurpose(json)
            "swipe" -> summarizeSwipePurpose(json)
            "drag" -> summarizeDragPurpose(json)
            "scroll" -> summarizeScrollPurpose(json)
            "Type", "Type_Name", "input_text" -> {
                val text = json.optString("text", "")
                if (text.isBlank()) {
                    "在当前输入框输入内容，推进任务。"
                } else {
                    "输入“${text.take(24)}”，完成当前输入框内容填写。"
                }
            }
            "Launch", "launch_app" -> {
                val app = json.optString("app", "").ifBlank { json.optString("app_name", "目标应用") }
                "打开$app，进入任务所需应用环境。"
            }
            "Back", "back" -> "返回上一层，离开当前无关页面或关闭弹窗。"
            "Home", "home" -> "回到桌面，重置当前应用导航上下文。"
            "press_key" -> summarizePressKeyPurpose(json)
            "key_event", "keyevent" -> summarizeKeyEventPurpose(json)
            "Wait", "wait" -> "等待页面加载或操作结果稳定。"
            "done" -> "结束任务并汇报结果。"
            "Take_over", "take_over" -> "请求用户介入处理登录、验证或敏感操作。"
            "Answer", "answer" -> {
                val answer = json.optString("answer", "").ifBlank { "当前查询结果" }
                "直接回答用户“${answer.take(36)}”，结束信息查询类任务。"
            }
            "Read_Clipboard", "ReadClipboard", "read_clipboard" ->
                "读取剪贴板内容，供后续输入验证码、链接或跨应用文本。"
            "Write_Clipboard", "WriteClipboard", "write_clipboard" ->
                "写入剪贴板，准备长文本粘贴或跨应用传递内容。"
            "Ask_User", "AskUser", "ask_user" -> {
                val question = json.optString("question", "").ifBlank { "下一步如何处理" }
                "向用户提问“${question.take(36)}”，等待明确回答后继续。"
            }
            "Note", "record_important_content", "record", "generate_or_update_todos", "todos" ->
                "记录当前页面信息，供后续总结或判断使用。"
            "Call_API", "call_api" -> {
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
            "" -> if (json.has("action")) {
                describeDoAction(json)
            } else {
                "未知动作元数据空；复用前需要确认动作类型。"
            }
            else -> "未知动作元数据 ${json.optString("_metadata", "空")}；复用前需要确认动作类型。"
        }
    }

    private fun describeDoAction(json: JSONObject): String {
        return when (val action = json.optString("action")) {
            "Tap", "Click", "tap", "click" -> describeTap(json)
            "Long Press", "long_press", "longpress" -> describePointAction("长按", json)
            "Double Tap", "double_tap", "doubletap" -> describePointAction("双击", json)
            "Swipe", "swipe" -> describeSwipe(json)
            "drag" -> describeDrag(json)
            "scroll" -> describeScroll(json)
            "Type", "Type_Name", "input_text" -> {
                val text = json.optString("text", "")
                "输入文本: ${text.ifBlank { "空文本" }}；复用 skill 时需确认当前焦点已经在目标输入框。"
            }
            "Launch", "launch_app" -> "启动应用: ${json.optString("app", json.optString("app_name", "未指定"))}；复用 skill 时先确认应用名能正确匹配安装包。"
            "Back", "back" -> "返回上一层页面；复用 skill 时需确认当前页面层级一致。"
            "Home", "home" -> "回到系统桌面；通常用于重置上下文或结束当前应用路径。"
            "press_key" -> describePressKey(json)
            "key_event", "keyevent" -> describeKeyEvent(json)
            "Wait", "wait" -> "等待 ${json.optString("duration", json.optString("seconds", "默认时长"))}；用于等待页面加载、动画或网络结果稳定。"
            "done" -> "结束任务并返回结果: ${json.optString("message", json.optString("reason", "任务完成"))}"
            "Take_over", "take_over" -> "请求用户介入: ${json.optString("message", "未提供说明")}。"
            "Answer", "answer" -> "回答用户: ${json.optString("answer", "未提供答案")}。"
            "Read_Clipboard", "ReadClipboard", "read_clipboard" ->
                "读取剪贴板；原因: ${json.optString("reason", json.optString("purpose", "未提供"))}。"
            "Write_Clipboard", "WriteClipboard", "write_clipboard" ->
                "写入剪贴板: ${json.optString("text", "空文本")}；原因: ${json.optString("reason", json.optString("purpose", "未提供"))}。"
            "Ask_User", "AskUser", "ask_user" -> describeAskUser(json)
            "Note", "record_important_content", "record", "generate_or_update_todos", "todos" ->
                "记录中间状态: ${json.optString("content", json.optString("todos", json.optString("message", "未提供说明")))}。"
            "Call_API", "call_api" -> "调用外部能力: ${json.optString("instruction", "未提供说明")}。"
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

    private fun summarizePressKeyPurpose(json: JSONObject): String {
        return when (json.optString("key", "").lowercase()) {
            "back" -> "返回上一层，离开当前无关页面或关闭弹窗。"
            "home" -> "回到桌面，重置当前应用导航上下文。"
            "recent", "recents", "overview" -> "打开最近任务视图，准备切换或检查后台应用。"
            else -> "执行系统按键 ${json.optString("key", "未指定")}，调整导航上下文。"
        }
    }

    private fun describePressKey(json: JSONObject): String {
        return when (json.optString("key", "").lowercase()) {
            "back" -> "执行系统返回键；复用 skill 时需确认当前页面层级一致。"
            "home" -> "执行系统主页键；通常用于重置上下文或结束当前应用路径。"
            "recent", "recents", "overview" -> "打开系统最近任务视图；适合切换应用或检查后台任务。"
            else -> "执行系统按键: ${json.optString("key", "未指定")}。"
        }
    }

    private fun summarizeKeyEventPurpose(json: JSONObject): String {
        return when (json.optString("key", "").replace("-", "_").lowercase()) {
            "back" -> "返回上一层，离开当前无关页面或关闭弹窗。"
            "home" -> "回到桌面，重置当前应用导航上下文。"
            "recent", "recents", "overview" -> "打开最近任务视图，准备切换或检查后台应用。"
            "notifications", "notification_shade", "notification" -> "打开通知栏，查看系统通知或验证码提示。"
            "quick_settings", "quicksettings", "settings_panel" -> "打开快捷设置面板，检查网络、蓝牙或系统开关状态。"
            "power_dialog", "power_menu", "global_actions", "power" -> "打开系统电源菜单，处理重启、关机或紧急系统操作。"
            "lock_screen", "lock" -> "锁定屏幕，完成安全或隐私相关系统操作。"
            else -> "执行系统按键事件 ${json.optString("key", "未指定")}，调整系统上下文。"
        }
    }

    private fun describeKeyEvent(json: JSONObject): String {
        return when (json.optString("key", "").replace("-", "_").lowercase()) {
            "notifications", "notification_shade", "notification" -> "打开通知栏；复用 skill 时需确认当前任务确实需要读取通知。"
            "quick_settings", "quicksettings", "settings_panel" -> "打开快捷设置面板；复用 skill 时需确认目标开关或状态仍可从该面板查看。"
            "power_dialog", "power_menu", "global_actions", "power" -> "打开系统电源菜单；这是敏感系统操作，复用前需要确认用户明确要求。"
            "lock_screen", "lock" -> "锁定屏幕；这是敏感系统操作，复用前需要确认不会中断任务。"
            else -> "执行系统按键事件: ${json.optString("key", "未指定")}。"
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
        val direction = json.optString("direction", "")
        if (direction.isNotBlank()) {
            return when (direction.lowercase()) {
                "up" -> "向上滑动列表，继续查找目标内容。"
                "down" -> "向下滑动列表，回看上方内容或调整位置。"
                "left" -> "向左滑动页面，切换到后续内容。"
                "right" -> "向右滑动页面，切换到前序内容。"
                else -> "按方向滑动当前页面，继续推进任务。"
            }
        }
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

    private fun summarizeDragPurpose(json: JSONObject): String {
        val start = readPoint(json, "start")
        val end = readPoint(json, "end")
        return if (start != null && end != null) {
            "从 (${start.first}, ${start.second}) 拖拽到 (${end.first}, ${end.second})，调整滑块、排序或拖动目标。"
        } else {
            "拖动当前目标，调整页面控件或目标位置。"
        }
    }

    private fun summarizeScrollPurpose(json: JSONObject): String {
        val value = json.optInt("value", 0)
        return when {
            value > 0 -> "向上滚动列表，继续查找下方内容。"
            value < 0 -> "向下滚动列表，回看上方内容。"
            else -> "滚动当前页面，调整可见区域。"
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
        val direction = json.optString("direction", "")
        if (direction.isNotBlank()) {
            return "方向滑动: $direction；复用 skill 时需确认当前页面可沿该方向滚动。"
        }
        val start = readPoint(json, "start")
        val end = readPoint(json, "end")
        return if (start != null && end != null) {
            "滑动路径: (${start.first}, ${start.second}) -> (${end.first}, ${end.second})；目的通常是滚动列表或切换页面，复用 skill 时需确认内容方向和可滚动区域。"
        } else {
            "滑动动作坐标不完整；复用 skill 时需确认滚动区域和方向。"
        }
    }

    private fun describeDrag(json: JSONObject): String {
        val start = readPoint(json, "start")
        val end = readPoint(json, "end")
        val duration = json.optLong("duration", 500L)
        return if (start != null && end != null) {
            "拖拽路径: (${start.first}, ${start.second}) -> (${end.first}, ${end.second})，时长: ${duration}ms；复用 skill 时需确认滑块或目标仍在相同语义位置。"
        } else {
            "拖拽当前目标；复用 skill 前需要补充起止坐标。"
        }
    }

    private fun describeScroll(json: JSONObject): String {
        val point = readPoint(json, "coordinates")
        val value = json.optInt("value", 0)
        val pointText = point?.let { "(${it.first}, ${it.second})" } ?: "默认中心区域"
        return "滚动区域: $pointText，滚动量: $value；正数向上滚动，负数向下滚动。"
    }

    private fun describeAskUser(json: JSONObject): String {
        val question = json.optString("question", "").ifBlank {
            json.optString("message", "未提供问题")
        }
        val options = json.optJSONArray("options")?.let { array ->
            (0 until array.length())
                .mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
        }.orEmpty()
        val optionText = if (options.isEmpty()) {
            "未提供固定选项"
        } else {
            options.joinToString(" / ")
        }
        return "请求用户回答: $question；选项: $optionText；Trace 会记录该协作点。"
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
