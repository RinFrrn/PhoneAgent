package com.mobileagent.phoneagent.harness.plan

enum class PreprocessedTaskType {
    SYSTEM_COMMAND,
    UI_INTERACTION
}

enum class PreprocessedExecutor {
    RULE_ENGINE,
    LLM
}

data class TaskPreprocessResult(
    val actionJson: String,
    val taskType: PreprocessedTaskType,
    val executor: PreprocessedExecutor,
    val skipLlm: Boolean,
    val confidence: Float,
    val reason: String,
    val finishAfterExecution: Boolean = skipLlm
) {
    fun toPlanDecision(): PlanDecision {
        return PlanDecision(
            thinking = reason,
            rawResponse = buildString {
                appendLine(reason)
                append(actionJson)
            },
            actionJson = actionJson,
            finishRequested = finishAfterExecution,
            source = PlanDecisionSource.TASK_PREPROCESSOR,
            executor = executor.name,
            taskType = taskType.name,
            confidence = confidence,
            skipLlm = skipLlm
        )
    }
}

class TaskPreprocessor {
    fun preprocess(instruction: String): TaskPreprocessResult? {
        val task = instruction.trim()
        if (task.isEmpty()) {
            return null
        }

        parseSensitiveOperation(task)?.let { return it }
        parseHome(task)?.let { return it }
        parseBack(task)?.let { return it }
        parseWait(task)?.let { return it }
        parseScreenSnapshot(task)?.let { return it }
        parseLaunch(task)?.let { return it }
        parseComplexTodo(task)?.let { return it }

        return null
    }

    private fun parseSensitiveOperation(task: String): TaskPreprocessResult? {
        val matchedKeyword = SENSITIVE_KEYWORDS.firstOrNull { task.contains(it, ignoreCase = true) }
            ?: return null
        val question = "检测到任务可能涉及敏感操作（$matchedKeyword）。是否确认继续自动化？"
        val actionJson = doActionJson(
            "Ask_User",
            "question" to question,
            "options" to listOf("确认继续", "取消任务"),
            "reason" to "敏感任务需要用户明确确认"
        )
        return TaskPreprocessResult(
            actionJson = actionJson,
            taskType = PreprocessedTaskType.SYSTEM_COMMAND,
            executor = PreprocessedExecutor.RULE_ENGINE,
            skipLlm = true,
            confidence = 0.96f,
            reason = "任务预处理命中敏感操作关键词“$matchedKeyword”：先请求用户确认，确认后再交给模型继续原任务。",
            finishAfterExecution = false
        )
    }

    private fun parseLaunch(task: String): TaskPreprocessResult? {
        val compoundLaunchPatterns = listOf(
            Pattern("""^(?:打开|启动)\s*(?<app>[\w\u4e00-\u9fa5·.\- ]+?)\s*(?:app|应用)?\s*(?:[，,。；;]|然后|接着|并且).+""", 0.9f),
            Pattern("""^(?:Open|Launch)\s+(?<app>[\w .\-]+?)\s*(?:app)?\s*(?:[,;.]|then)\s*.+""", 0.9f, setOf(RegexOption.IGNORE_CASE)),
            Pattern("""^(?<app>[\w\u4e00-\u9fa5·.\-]{2,12})(?:创作|发布|发送|搜索|查找|购买|下单|刷|浏览).+""", 0.85f),
            Pattern("""^在\s*(?<app>[\w\u4e00-\u9fa5·.\- ]+?)\s*(?:给|向|跟|和|找|搜|查).+""", 0.85f)
        )
        findAppMatch(task, compoundLaunchPatterns)?.let { (app, confidence) ->
            return launchResult(
                appName = app,
                skipLlm = false,
                confidence = confidence,
                reason = "任务预处理命中复合启动指令：先打开$app，再交给模型继续完成原任务。"
            )
        }

        val directLaunchPatterns = listOf(
            Pattern("""^(?:打开|启动)\s*(?<app>[\w\u4e00-\u9fa5·.\- ]+?)\s*(?:app|应用)?$""", 0.95f),
            Pattern("""^(?<app>[\w\u4e00-\u9fa5·.\- ]+?)\s*(?:app|应用)$""", 0.9f),
            Pattern("""^(?:Open|Launch)\s+(?<app>[\w .\-]+?)\s*(?:app)?$""", 0.95f, setOf(RegexOption.IGNORE_CASE))
        )
        findAppMatch(task, directLaunchPatterns)?.let { (app, confidence) ->
            return launchResult(
                appName = app,
                skipLlm = true,
                confidence = confidence,
                reason = "任务预处理命中启动应用指令：直接打开$app，跳过本步模型请求。"
            )
        }

        return null
    }

    private fun parseComplexTodo(task: String): TaskPreprocessResult? {
        val steps = splitComplexTask(task)
        if (steps.size < COMPLEX_TASK_MIN_STEPS) {
            return null
        }
        val todos = steps.joinToString("\n") { step -> "- [ ] $step" }
        return TaskPreprocessResult(
            actionJson = doActionJson(
                "Note",
                "todos" to todos,
                "reason" to "复杂任务先建立可追踪 TODO"
            ),
            taskType = PreprocessedTaskType.UI_INTERACTION,
            executor = PreprocessedExecutor.RULE_ENGINE,
            skipLlm = true,
            confidence = 0.82f,
            reason = "任务预处理识别为 ${steps.size} 步复杂任务：先记录 TODO 计划，下一步交给模型按当前页面继续执行。",
            finishAfterExecution = false
        )
    }

    private fun splitComplexTask(task: String): List<String> {
        val normalized = task
            .replace(Regex("""\s+(then|and then|after that|finally)\s+""", RegexOption.IGNORE_CASE), "，")
            .replace(Regex("""(?:然后|接着|之后|随后|最后|并且|同时|再)"""), "，")
        return normalized
            .split(Regex("""[，,。；;]+"""))
            .map { segment ->
                segment
                    .trim()
                    .removePrefix("先")
                    .removePrefix("再")
                    .trim()
            }
            .filter { segment ->
                segment.length >= 2 &&
                    segment !in setOf("帮我", "请帮我", "麻烦", "我要", "我想")
            }
            .take(6)
    }

    private fun parseHome(task: String): TaskPreprocessResult? {
        val patterns = listOf(
            Pattern("""^(?:返回|回到)\s*(?:桌面|主屏幕)$""", 0.95f),
            Pattern("""^(?:Go|Back to)\s+home$""", 0.95f, setOf(RegexOption.IGNORE_CASE)),
            Pattern("""^Home$""", 0.9f, setOf(RegexOption.IGNORE_CASE))
        )
        val confidence = findPattern(task, patterns) ?: return null
        return directResult(
            action = "Home",
            confidence = confidence,
            reason = "任务预处理命中返回桌面指令：直接执行 Home，跳过本步模型请求。"
        )
    }

    private fun parseBack(task: String): TaskPreprocessResult? {
        val patterns = listOf(
            Pattern("""^(?:返回|后退)$""", 0.95f),
            Pattern("""^Back$""", 0.95f, setOf(RegexOption.IGNORE_CASE))
        )
        val confidence = findPattern(task, patterns) ?: return null
        return directResult(
            action = "Back",
            confidence = confidence,
            reason = "任务预处理命中返回上一级指令：直接执行 Back，跳过本步模型请求。"
        )
    }

    private fun parseWait(task: String): TaskPreprocessResult? {
        val patterns = listOf(
            Pattern("""^(?:等待|等一下|稍等)(?<seconds>\d+)?\s*(?:秒|seconds?)?$""", 0.9f, setOf(RegexOption.IGNORE_CASE)),
            Pattern("""^Wait\s*(?<seconds>\d+)?\s*(?:seconds?)?$""", 0.9f, setOf(RegexOption.IGNORE_CASE))
        )
        for (pattern in patterns) {
            val match = Regex(pattern.value, pattern.options).matchEntire(task) ?: continue
            val seconds = match.groups["seconds"]?.value?.toIntOrNull()?.coerceIn(1, 30) ?: 1
            val actionJson = doActionJson("Wait", "duration" to "$seconds seconds")
            return TaskPreprocessResult(
                actionJson = actionJson,
                taskType = PreprocessedTaskType.SYSTEM_COMMAND,
                executor = PreprocessedExecutor.RULE_ENGINE,
                skipLlm = true,
                confidence = pattern.confidence,
                reason = "任务预处理命中等待指令：直接等待 ${seconds} 秒，跳过本步模型请求。"
            )
        }
        return null
    }

    private fun parseScreenSnapshot(task: String): TaskPreprocessResult? {
        val patterns = listOf(
            Pattern("""^(?:截[个一张]?屏|截图|屏幕截图|保存当前屏幕|记录当前屏幕|问屏)$""", 0.95f),
            Pattern("""^(?:Screenshot|Capture|Capture screen|Screen capture)$""", 0.95f, setOf(RegexOption.IGNORE_CASE))
        )
        val confidence = findPattern(task, patterns) ?: return null
        val message = "已采集当前屏幕观察并写入本次 Trace；视觉/混合模式包含截图输入，无障碍模式包含结构化屏幕文本。"
        return TaskPreprocessResult(
            actionJson = finishActionJson(message),
            taskType = PreprocessedTaskType.SYSTEM_COMMAND,
            executor = PreprocessedExecutor.RULE_ENGINE,
            skipLlm = true,
            confidence = confidence,
            reason = "任务预处理命中屏幕观察快照指令：复用本步观察结果写入 Trace，跳过模型请求。"
        )
    }

    private fun directResult(action: String, confidence: Float, reason: String): TaskPreprocessResult {
        val actionJson = doActionJson(action)
        return TaskPreprocessResult(
            actionJson = actionJson,
            taskType = PreprocessedTaskType.SYSTEM_COMMAND,
            executor = PreprocessedExecutor.RULE_ENGINE,
            skipLlm = true,
            confidence = confidence,
            reason = reason
        )
    }

    private fun launchResult(
        appName: String,
        skipLlm: Boolean,
        confidence: Float,
        reason: String
    ): TaskPreprocessResult {
        val actionJson = doActionJson("Launch", "app" to appName)
        return TaskPreprocessResult(
            actionJson = actionJson,
            taskType = PreprocessedTaskType.SYSTEM_COMMAND,
            executor = PreprocessedExecutor.RULE_ENGINE,
            skipLlm = skipLlm,
            confidence = confidence,
            reason = reason
        )
    }

    private fun findPattern(task: String, patterns: List<Pattern>): Float? {
        return patterns.firstOrNull { Regex(it.value, it.options).matches(task) }?.confidence
    }

    private fun findAppMatch(task: String, patterns: List<Pattern>): Pair<String, Float>? {
        val blockedImplicitAppNames = setOf("帮我", "请帮我", "麻烦", "我要", "我想")
        for (pattern in patterns) {
            val match = Regex(pattern.value, pattern.options).matchEntire(task) ?: continue
            val appName = match.groups["app"]?.value?.trim()?.removeSuffix("app")?.trim()
            if (!appName.isNullOrBlank() && appName !in blockedImplicitAppNames) {
                return appName to pattern.confidence
            }
        }
        return null
    }

    private fun doActionJson(action: String, vararg fields: Pair<String, Any>): String {
        val extraFields = fields.joinToString(separator = "") { (key, value) ->
            "," + quoteJson(key) + ":" + encodeJsonValue(value)
        }
        return """{"_metadata":"do","action":${quoteJson(action)}$extraFields}"""
    }

    private fun finishActionJson(message: String): String {
        return """{"_metadata":"finish","message":${quoteJson(message)}}"""
    }

    private fun quoteJson(value: String): String {
        return buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
        }
    }

    private fun encodeJsonValue(value: Any): String {
        return when (value) {
            is String -> quoteJson(value)
            is List<*> -> value.joinToString(prefix = "[", postfix = "]") { item ->
                quoteJson(item?.toString().orEmpty())
            }
            else -> quoteJson(value.toString())
        }
    }

    private data class Pattern(
        val value: String,
        val confidence: Float,
        val options: Set<RegexOption> = emptySet()
    )

    private companion object {
        const val COMPLEX_TASK_MIN_STEPS = 3

        val SENSITIVE_KEYWORDS = listOf(
            "转账",
            "付款",
            "支付",
            "付钱",
            "买单",
            "下单",
            "提交订单",
            "立即购买",
            "充值",
            "提现",
            "借款",
            "贷款",
            "还款",
            "输入密码",
            "密码",
            "验证码",
            "登录",
            "登陆",
            "人脸",
            "实名认证",
            "实名",
            "绑定银行卡",
            "解绑银行卡",
            "修改密码",
            "注销账号",
            "删除账号",
            "银行",
            "证券",
            "股票"
        )
    }
}
