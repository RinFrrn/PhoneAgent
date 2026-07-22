package com.mobileagent.phoneagent.harness.trace

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mobileagent.phoneagent.action.ClipboardTrace
import com.mobileagent.phoneagent.action.UserInteractionRequest
import com.mobileagent.phoneagent.harness.act.AppLaunchTrace
import com.mobileagent.phoneagent.harness.act.ExecutionHumanizationTrace
import com.mobileagent.phoneagent.harness.act.ExecutionResult
import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.plan.PlanDecision
import com.mobileagent.phoneagent.harness.runtime.RuntimeWarning
import com.mobileagent.phoneagent.harness.verify.VerificationResult
import com.mobileagent.phoneagent.model.ContentItem
import com.mobileagent.phoneagent.model.ImageUrl
import com.mobileagent.phoneagent.utils.LogSanitizer

object TraceSanitizer {
    const val DATA_POLICY = "MINIMIZED_V1"

    fun sanitizeSession(session: SessionTrace): SessionTrace {
        val sensitiveValues = session.steps
            .flatMap(::stepSensitiveValues)
            .toSet()
        return session.copy(
            taskGoal = sanitizeRelatedText(session.taskGoal, sensitiveValues),
            mode = sanitizeText(session.mode),
            modelProvider = session.modelProvider?.let { sanitizeRelatedText(it, sensitiveValues) },
            modelDisplayName = session.modelDisplayName?.let { sanitizeRelatedText(it, sensitiveValues) },
            modelName = session.modelName?.let { sanitizeRelatedText(it, sensitiveValues) },
            modelBaseUrl = session.modelBaseUrl?.let { sanitizeRelatedText(it, sensitiveValues) },
            outcomeMessage = session.outcomeMessage?.let { sanitizeRelatedText(it, sensitiveValues) },
            totalSteps = session.steps.size,
            steps = session.steps.map { sanitizeStep(it, sensitiveValues) },
            status = session.status ?: inferStatus(session),
            dataPolicy = DATA_POLICY
        )
    }

    fun sanitizeStep(step: StepTrace): StepTrace {
        return sanitizeStep(step, stepSensitiveValues(step))
    }

    private fun sanitizeStep(
        step: StepTrace,
        sensitiveValues: Collection<String>
    ): StepTrace {
        return step.copy(
            observationBefore = sanitizeObservation(step.observationBefore, sensitiveValues),
            decision = step.decision?.let { sanitizeDecision(it, sensitiveValues) },
            execution = step.execution?.let { sanitizeExecution(it, sensitiveValues) },
            observationAfter = step.observationAfter?.let { sanitizeObservation(it, sensitiveValues) },
            verification = step.verification?.let { sanitizeVerification(it, sensitiveValues) },
            errorMessage = step.errorMessage?.let { sanitizeRelatedText(it, sensitiveValues) },
            recovery = step.recovery?.copy(
                reason = sanitizeRelatedText(step.recovery.reason, sensitiveValues)
            ),
            runtimeWarnings = step.runtimeWarnings.map { sanitizeWarning(it, sensitiveValues) }
        )
    }

    fun sanitizeHistoryEntry(entry: TaskHistoryEntry): TaskHistoryEntry {
        return entry.copy(
            taskGoal = sanitizeText(entry.taskGoal),
            modelProvider = sanitizeNullableText(entry.modelProvider),
            modelDisplayName = sanitizeNullableText(entry.modelDisplayName),
            modelName = sanitizeNullableText(entry.modelName),
            modelBaseUrl = sanitizeNullableText(entry.modelBaseUrl),
            outcomeMessage = sanitizeNullableText(entry.outcomeMessage)
        )
    }

    fun sanitizeNullableText(value: String?): String? = value?.let(::sanitizeText)

    fun sanitizeText(value: String): String {
        return LogSanitizer.sanitize(value)
            .replace(DATA_URL_REGEX, REDACTED_IMAGE)
            .replace(LONG_BASE64_REGEX, REDACTED_BINARY)
            .replace(JSON_TEXT_FIELD_REGEX) { match ->
                "${match.groupValues[1]}$REDACTED_TEXT${match.groupValues[3]}"
            }
            .replace(PYTHON_TEXT_FIELD_REGEX) { match ->
                "${match.groupValues[1]}$REDACTED_TEXT${match.groupValues[3]}"
            }
            .replace(SECRET_LIKE_TOKEN_REGEX, REDACTED_TEXT)
            .replace(EMAIL_REGEX, REDACTED_EMAIL)
            .replace(LONG_NUMBER_REGEX, REDACTED_NUMBER)
            .replace(SIX_DIGIT_CODE_REGEX, REDACTED_CODE)
    }

    fun redactedImageLength(url: String): Int? {
        if (!url.startsWith(REDACTED_IMAGE_PREFIX)) {
            return null
        }
        return url.removePrefix(REDACTED_IMAGE_PREFIX).toIntOrNull()
    }

    private fun inferStatus(session: SessionTrace): TaskHistoryStatus {
        return when {
            session.completedAt == null -> TaskHistoryStatus.RUNNING
            session.success == true -> TaskHistoryStatus.SUCCEEDED
            session.success == false -> TaskHistoryStatus.FAILED
            else -> TaskHistoryStatus.STOPPED
        }
    }

    internal fun stepSensitiveValues(step: StepTrace): Set<String> {
        return buildSet {
            step.decision?.actionJson?.let { addAll(sensitiveActionValues(it)) }
            step.execution?.let { execution ->
                addAll(sensitiveActionValues(execution.actionJson))
                execution.humanizationTrace?.let { trace ->
                    addAll(sensitiveActionValues(trace.originalActionJson))
                    addAll(sensitiveActionValues(trace.transformedActionJson))
                }
                execution.clipboardTrace?.contentPreview
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
        }
    }

    private fun sanitizeObservation(
        observation: Observation,
        sensitiveValues: Collection<String>
    ): Observation {
        return observation.copy(
            currentApp = observation.currentApp?.let { sanitizeRelatedText(it, sensitiveValues) },
            currentPackage = observation.currentPackage?.let { sanitizeRelatedText(it, sensitiveValues) },
            contentItems = observation.contentItems.map { sanitizeContentItem(it, sensitiveValues) },
            failureMessage = observation.failureMessage?.let { sanitizeRelatedText(it, sensitiveValues) }
        )
    }

    private fun sanitizeContentItem(
        item: ContentItem,
        sensitiveValues: Collection<String>
    ): ContentItem {
        if (item.type == "image_url" && item.imageUrl != null) {
            return item.copy(
                text = null,
                imageUrl = ImageUrl(redactedImageReference(item.imageUrl.url.length))
            )
        }
        return item.copy(
            text = item.text?.let { sanitizeRelatedText(it, sensitiveValues) },
            imageUrl = null
        )
    }

    private fun sanitizeDecision(
        decision: PlanDecision,
        relatedSensitiveValues: Collection<String> = emptySet()
    ): PlanDecision {
        val sensitiveValues = sensitiveActionValues(decision.actionJson) + relatedSensitiveValues
        return decision.copy(
            thinking = sanitizeRelatedText(decision.thinking, sensitiveValues),
            rawResponse = sanitizeRelatedText(decision.rawResponse, sensitiveValues),
            actionJson = sanitizeActionJson(decision.actionJson),
            executor = sanitizeNullableText(decision.executor),
            taskType = sanitizeNullableText(decision.taskType)
        )
    }

    private fun sanitizeExecution(
        execution: ExecutionResult,
        relatedSensitiveValues: Collection<String> = emptySet()
    ): ExecutionResult {
        val sensitiveValues = sensitiveActionValues(execution.actionJson) +
            listOfNotNull(execution.clipboardTrace?.contentPreview?.takeIf { it.isNotBlank() }) +
            relatedSensitiveValues
        return execution.copy(
            message = execution.message?.let { sanitizeRelatedText(it, sensitiveValues) },
            actionJson = sanitizeActionJson(execution.actionJson),
            launchTrace = execution.launchTrace?.let(::sanitizeLaunchTrace),
            humanizationTrace = execution.humanizationTrace?.let(::sanitizeHumanizationTrace),
            taskNote = execution.taskNote?.let(::sanitizeTaskNote),
            userInteractionRequest = execution.userInteractionRequest?.let(::sanitizeUserInteractionRequest),
            clipboardTrace = execution.clipboardTrace?.let(::sanitizeClipboardTrace)
        )
    }

    private fun sanitizeLaunchTrace(trace: AppLaunchTrace): AppLaunchTrace {
        return trace.copy(
            targetAppName = sanitizeText(trace.targetAppName),
            targetPackage = sanitizeNullableText(trace.targetPackage),
            actualAppName = sanitizeNullableText(trace.actualAppName),
            popupButtonText = sanitizeNullableText(trace.popupButtonText),
            beforePackage = sanitizeNullableText(trace.beforePackage),
            beforeApp = sanitizeNullableText(trace.beforeApp),
            afterPackage = sanitizeNullableText(trace.afterPackage),
            afterApp = sanitizeNullableText(trace.afterApp),
            handoffRequestId = trace.handoffRequestId,
            message = sanitizeNullableText(trace.message)
        )
    }

    private fun sanitizeHumanizationTrace(trace: ExecutionHumanizationTrace): ExecutionHumanizationTrace {
        return trace.copy(
            originalActionJson = sanitizeActionJson(trace.originalActionJson),
            transformedActionJson = sanitizeActionJson(trace.transformedActionJson),
            reason = sanitizeText(trace.reason)
        )
    }

    private fun sanitizeTaskNote(note: TaskNote): TaskNote {
        return note.copy(
            content = sanitizeText(note.content),
            category = sanitizeNullableText(note.category),
            reason = sanitizeNullableText(note.reason)
        )
    }

    private fun sanitizeUserInteractionRequest(request: UserInteractionRequest): UserInteractionRequest {
        return request.copy(
            question = sanitizeText(request.question),
            options = request.options.map(::sanitizeText),
            reason = sanitizeText(request.reason)
        )
    }

    private fun sanitizeClipboardTrace(trace: ClipboardTrace): ClipboardTrace {
        return trace.copy(
            contentPreview = if (trace.contentPreview.isBlank()) "" else REDACTED_TEXT,
            reason = sanitizeText(trace.reason)
        )
    }

    private fun sanitizeVerification(
        verification: VerificationResult,
        sensitiveValues: Collection<String>
    ): VerificationResult {
        return verification.copy(
            reason = sanitizeRelatedText(verification.reason, sensitiveValues),
            observedChange = verification.observedChange?.let {
                sanitizeRelatedText(it, sensitiveValues)
            }
        )
    }

    private fun sanitizeWarning(
        warning: RuntimeWarning,
        sensitiveValues: Collection<String>
    ): RuntimeWarning {
        return warning.copy(message = sanitizeRelatedText(warning.message, sensitiveValues))
    }

    private fun sanitizeActionJson(actionJson: String): String {
        val root = runCatching { JsonParser.parseString(actionJson) }.getOrNull()
            ?: return sanitizeText(actionJson)
        sanitizeJsonElement(root, actionName = root.actionName())
        return root.toString()
    }

    private fun sensitiveActionValues(actionJson: String): Set<String> {
        val root = runCatching { JsonParser.parseString(actionJson) }.getOrNull() ?: return emptySet()
        val actionName = root.actionName()
        val values = linkedSetOf<String>()
        collectSensitiveValues(root, actionName, values)
        return values.filterTo(linkedSetOf()) { it.isNotBlank() }
    }

    private fun collectSensitiveValues(
        element: JsonElement,
        actionName: String,
        values: MutableSet<String>
    ) {
        when {
            element.isJsonObject -> {
                val json = element.asJsonObject
                val localActionName = json.actionName().ifBlank { actionName }
                json.entrySet().forEach { (key, value) ->
                    val normalizedKey = key.lowercase().replace("-", "_")
                    val sensitive = normalizedKey in secretKeys ||
                        (normalizedKey == "text" && localActionName in textInputActions)
                    if (sensitive && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                        values += value.asString
                    } else if (value.isJsonObject || value.isJsonArray) {
                        collectSensitiveValues(value, localActionName, values)
                    }
                }
            }
            element.isJsonArray -> element.asJsonArray.forEach { value ->
                collectSensitiveValues(value, actionName, values)
            }
        }
    }

    internal fun sanitizeRelatedText(value: String, sensitiveValues: Collection<String>): String {
        return sensitiveValues.fold(sanitizeText(value)) { sanitized, sensitiveValue ->
            sanitized.replace(sensitiveValue, REDACTED_TEXT)
        }
    }

    private fun sanitizeJsonElement(element: JsonElement, actionName: String) {
        when {
            element.isJsonObject -> sanitizeJsonObject(element.asJsonObject, actionName)
            element.isJsonArray -> sanitizeJsonArray(element.asJsonArray, actionName)
        }
    }

    private fun sanitizeJsonObject(json: JsonObject, inheritedActionName: String) {
        val actionName = json.actionName().ifBlank { inheritedActionName }
        json.entrySet().toList().forEach { (key, value) ->
            val normalizedKey = key.lowercase().replace("-", "_")
            when {
                normalizedKey in secretKeys -> json.addProperty(key, REDACTED_TEXT)
                normalizedKey == "text" && actionName in textInputActions -> {
                    json.addProperty(key, REDACTED_TEXT)
                }
                value.isJsonObject || value.isJsonArray -> sanitizeJsonElement(value, actionName)
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
                    json.addProperty(key, sanitizeText(value.asString))
                }
            }
        }
    }

    private fun sanitizeJsonArray(json: JsonArray, actionName: String) {
        for (index in 0 until json.size()) {
            val value = json[index]
            when {
                value.isJsonObject || value.isJsonArray -> sanitizeJsonElement(value, actionName)
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
                    json.set(index, com.google.gson.JsonPrimitive(sanitizeText(value.asString)))
                }
            }
        }
    }

    private fun JsonElement.actionName(): String {
        if (!isJsonObject) return ""
        return asJsonObject.get("action")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.trim()
            ?.replace("-", "_")
            ?.lowercase()
            .orEmpty()
    }

    private fun JsonObject.actionName(): String {
        return get("action")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.trim()
            ?.replace("-", "_")
            ?.lowercase()
            .orEmpty()
    }

    private fun redactedImageReference(originalChars: Int): String {
        return "$REDACTED_IMAGE_PREFIX$originalChars"
    }

    private val textInputActions = setOf("type", "type_name", "input_text", "write_clipboard", "writeclipboard")
    private val secretKeys = setOf(
        "password",
        "passwd",
        "pwd",
        "token",
        "access_token",
        "refresh_token",
        "api_key",
        "apikey",
        "authorization",
        "secret",
        "private_key"
    )

    private val DATA_URL_REGEX = Regex(
        """data:image/[^;,\s]+;base64,[A-Za-z0-9+/=\r\n]+""",
        RegexOption.IGNORE_CASE
    )
    private val LONG_BASE64_REGEX = Regex("""(?<![A-Za-z0-9+/=])[A-Za-z0-9+/=]{256,}(?![A-Za-z0-9+/=])""")
    private val JSON_TEXT_FIELD_REGEX = Regex("""(?i)("text"\s*:\s*")((?:\\.|[^"\\])*)(")""")
    private val PYTHON_TEXT_FIELD_REGEX = Regex("""(?i)(\btext\s*=\s*['"])(.*?)(['"])""")
    private val EMAIL_REGEX = Regex("""\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""", RegexOption.IGNORE_CASE)
    private val SECRET_LIKE_TOKEN_REGEX = Regex(
        """(?<![A-Za-z0-9_-])[A-Za-z0-9._-]*(?:password|passwd|pwd|secret|token)[A-Za-z0-9._-]*(?![A-Za-z0-9_-])""",
        RegexOption.IGNORE_CASE
    )
    private val LONG_NUMBER_REGEX = Regex("""(?<!\d)\d{12,19}(?!\d)""")
    private val SIX_DIGIT_CODE_REGEX = Regex("""(?<!\d)\d{6}(?!\d)""")

    private const val REDACTED_TEXT = "[REDACTED]"
    private const val REDACTED_IMAGE = "[IMAGE_REDACTED]"
    private const val REDACTED_BINARY = "[BINARY_REDACTED]"
    private const val REDACTED_EMAIL = "[EMAIL_REDACTED]"
    private const val REDACTED_NUMBER = "[NUMBER_REDACTED]"
    private const val REDACTED_CODE = "[CODE_REDACTED]"
    private const val REDACTED_IMAGE_PREFIX = "trace-redacted://image/chars/"
}
