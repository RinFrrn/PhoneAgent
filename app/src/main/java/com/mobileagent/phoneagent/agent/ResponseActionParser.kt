package com.mobileagent.phoneagent.agent

import android.util.Log

class ResponseActionParser {
    private val tag = "ResponseActionParser"

    fun parseActionFromResponse(response: String): String {
        logDebug("解析操作响应: ${response.take(200)}...")
        val actionSegment = extractActionSegment(response)
        normalizeJsonAction(actionSegment)?.let { json ->
            logDebug("从动作段提取到 JSON: $json")
            return json
        }
        completeTruncatedJson(actionSegment)?.let { completed ->
            normalizeJsonAction(completed)?.let { json ->
                logDebug("补全截断 JSON 后提取到动作: $json")
                return json
            }
        }

        extractFirstValidJson(actionSegment)?.let { json ->
            logDebug("从动作段提取到完整 JSON: $json")
            return json
        }

        return parseActionFromCode(actionSegment)
    }

    private fun logDebug(message: String) {
        runCatching { Log.d(tag, message) }
    }

    private fun extractActionSegment(response: String): String {
        extractTagContent(response, "tool_call")?.let { return it }
        extractTagContent(response, "answer")?.let { return it }
        extractJsonAfterThinking(response)?.let { return it }
        extractBoxAction(response)?.let { return it }
        extractMultilineAction(response)?.let { return it }
        return response
    }

    private fun extractJsonAfterThinking(response: String): String? {
        val thinkingEnd = response.indexOf("</thinking>")
        if (thinkingEnd != -1) {
            return response.substring(thinkingEnd + "</thinking>".length)
                .trim()
                .takeIf { it.contains("{") }
        }

        val thinkEnd = response.indexOf("</think>")
        if (thinkEnd != -1) {
            return response.substring(thinkEnd + "</think>".length)
                .trim()
                .takeIf { it.contains("{") }
        }

        val thinkingStart = response.indexOf("<thinking>")
        if (thinkingStart != -1) {
            val jsonStart = response.indexOf("{", thinkingStart + "<thinking>".length)
            if (jsonStart != -1) {
                return response.substring(jsonStart).trim()
            }
        }

        return null
    }

    private fun extractTagContent(response: String, tagName: String): String? {
        val startTag = "<$tagName>"
        val endTag = "</$tagName>"
        val start = response.indexOf(startTag)
        if (start == -1) {
            return null
        }
        val contentStart = start + startTag.length
        val end = response.indexOf(endTag, contentStart)
        return if (end == -1) {
            response.substring(contentStart).trim()
        } else {
            response.substring(contentStart, end).trim()
        }.takeIf { it.isNotBlank() }
    }

    private fun extractBoxAction(response: String): String? {
        val pattern = Regex("""<\|begin_of_box\|>(.*?)<\|end_of_box\|>""", RegexOption.DOT_MATCHES_ALL)
        val boxes = pattern.findAll(response)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .toList()
        return when {
            boxes.size >= 2 -> boxes[1]
            boxes.size == 1 -> boxes[0]
            else -> null
        }
    }

    private fun extractMultilineAction(response: String): String? {
        val actionLines = mutableListOf<String>()
        var inAction = false
        lineLoop@ for (line in response.lines()) {
            val trimmed = line.trim()
            when (trimmed) {
                "{action}" -> {
                    inAction = true
                    continue@lineLoop
                }
                "{think}", "{thinking}" -> {
                    if (inAction) break@lineLoop
                }
            }
            if (inAction && trimmed.isNotBlank()) {
                actionLines += trimmed
            }
        }
        return actionLines.joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun normalizeJsonAction(code: String): String? {
        val json = code.trim()
        if (!json.startsWith("{") || !json.endsWith("}")) {
            return null
        }
        if (json.contains(Regex(""""_metadata"\s*:"""))) {
            return json
        }
        extractJsonValue(json, "action")?.let { actionValue ->
            val normalizedJson = normalizeTerminalAlias(json, actionValue)
            return when {
                actionValue.startsWith("{") -> actionValue
                actionValue.startsWith("[") -> firstJsonAction(actionValue)
                actionValue.startsWith("\"") -> {
                    val actionText = unquote(actionValue).trim()
                    if (looksLikeLegacyActionCommand(actionText)) {
                        parseActionFromCode(actionText)
                    } else if (hasOtherFields(normalizedJson, "action")) {
                        normalizedJson
                    } else {
                        parseActionFromCode(actionText)
                    }
                }
                else -> normalizedJson
            }
        }
        return null
    }

    private fun looksLikeLegacyActionCommand(actionText: String): Boolean {
        return actionText.startsWith("do(") || actionText.startsWith("finish(")
    }

    private fun normalizeTerminalAlias(json: String, actionValue: String): String {
        if (!actionValue.equals("\"finish\"", ignoreCase = true)) {
            return json
        }
        return json.replaceFirst(
            Regex(""""action"\s*:\s*"finish"""", RegexOption.IGNORE_CASE),
            """"action":"done""""
        )
    }

    private fun completeTruncatedJson(code: String): String? {
        val trimmed = code.trim()
        if (!trimmed.startsWith("{")) {
            return null
        }
        val closers = mutableListOf<Char>()
        var inString = false
        var escaped = false
        for (char in trimmed) {
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> closers.add('}')
                '[' -> closers.add(']')
                '}', ']' -> {
                    if (closers.isEmpty() || closers.removeAt(closers.lastIndex) != char) {
                        return null
                    }
                }
            }
        }
        if (inString || closers.isEmpty()) {
            return null
        }
        return trimmed + closers.asReversed().joinToString("")
    }

    private fun hasOtherFields(json: String, ignoredKey: String): Boolean {
        val objectBody = json.trim().removePrefix("{").removeSuffix("}")
        var depth = 0
        var inString = false
        var escaped = false
        val topLevel = mutableListOf<String>()
        val current = StringBuilder()
        for (char in objectBody) {
            if (inString) {
                current.append(char)
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
                continue
            }
            when (char) {
                '"' -> {
                    inString = true
                    current.append(char)
                }
                '{', '[' -> {
                    depth++
                    current.append(char)
                }
                '}', ']' -> {
                    depth--
                    current.append(char)
                }
                ',' -> {
                    if (depth == 0) {
                        topLevel += current.toString()
                        current.clear()
                    } else {
                        current.append(char)
                    }
                }
                else -> current.append(char)
            }
        }
        if (current.isNotBlank()) {
            topLevel += current.toString()
        }
        return topLevel.any { !it.trim().startsWith("\"$ignoredKey\"") }
    }

    private fun firstJsonAction(array: String): String? {
        val body = array.trim().removePrefix("[").removeSuffix("]").trim()
        if (body.isBlank()) {
            return null
        }
        val first = extractFirstArrayItem(body) ?: return null
        return when {
            first.startsWith("{") -> first
            first.startsWith("\"") -> parseActionFromCode(unquote(first))
            else -> null
        }
    }

    private fun extractFirstArrayItem(body: String): String? {
        var depth = 0
        var inString = false
        var escaped = false
        val item = StringBuilder()
        for (char in body) {
            if (inString) {
                item.append(char)
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
                continue
            }
            when (char) {
                '"' -> {
                    inString = true
                    item.append(char)
                }
                '{', '[' -> {
                    depth++
                    item.append(char)
                }
                '}', ']' -> {
                    depth--
                    item.append(char)
                }
                ',' -> if (depth == 0) return item.toString().trim() else item.append(char)
                else -> item.append(char)
            }
        }
        return item.toString().trim().takeIf { it.isNotBlank() }
    }

    private fun extractJsonValue(json: String, key: String): String? {
        val keyMatch = Regex(""""${Regex.escape(key)}"\s*:""").find(json) ?: return null
        var index = keyMatch.range.last + 1
        while (index < json.length && json[index].isWhitespace()) {
            index++
        }
        if (index >= json.length) {
            return null
        }
        return when (json[index]) {
            '{' -> extractBalanced(json, index, '{', '}')
            '[' -> extractBalanced(json, index, '[', ']')
            '"' -> extractQuoted(json, index)
            else -> {
                val end = json.indexOf(',', index).takeIf { it != -1 } ?: json.indexOf('}', index).takeIf { it != -1 } ?: json.length
                json.substring(index, end).trim()
            }
        }
    }

    private fun extractBalanced(text: String, start: Int, open: Char, close: Char): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(start, index + 1).trim()
                    }
                }
            }
        }
        return null
    }

    private fun extractQuoted(text: String, start: Int): String? {
        var escaped = false
        for (index in start + 1 until text.length) {
            val char = text[index]
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '"') {
                return text.substring(start, index + 1)
            }
        }
        return null
    }

    private fun unquote(value: String): String {
        return value.trim().removePrefix("\"").removeSuffix("\"")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun extractFirstValidJson(response: String): String? {
        var braceCount = 0
        var startIndex = -1

        for (i in response.indices) {
            when (response[i]) {
                '{' -> {
                    if (startIndex == -1) startIndex = i
                    braceCount++
                }
                '}' -> {
                    braceCount--
                    if (braceCount == 0 && startIndex != -1) {
                        val jsonStr = response.substring(startIndex, i + 1)
                        val normalized = normalizeJsonAction(jsonStr)
                        if (normalized != null) {
                            return normalized
                        }
                        startIndex = -1
                    }
                }
            }
        }

        return null
    }

    private fun parseActionFromCode(code: String): String {
        val json = linkedMapOf<String, Any>()

        when {
            code.contains("finish(") -> {
                json["_metadata"] = "finish"
                val messageMatch = """message=["']([^"']+)["']""".toRegex().find(code)
                if (messageMatch != null) {
                    json["message"] = messageMatch.groupValues[1]
                }
            }
            code.contains("do(") -> {
                json["_metadata"] = "do"
                putOptionalArgument(json, code, "purpose")
                putOptionalArgument(json, code, "message")
                putOptionalArgument(json, code, "instruction")
                putOptionalArgument(json, code, "question")
                putOptionalArgument(json, code, "reason")
                putOptionalArgument(json, code, "answer")
                putOptionalArgument(json, code, "text")
                putOptionalArgument(json, code, "content")
                putOptionalArgument(json, code, "category")
                putOptionalArgument(json, code, "todos")
                putOptionalArgument(json, code, "app_name")
                putOptionalBooleanArgument(json, code, "success")
                val actionMatch = """action=["']([^"']+)["']""".toRegex().find(code)
                if (actionMatch != null) {
                    val action = actionMatch.groupValues[1]
                    json["action"] = action

                    when (action) {
                        "Tap", "Click" -> {
                            var elementMatch = """element=\[(\d+),\s*(\d+)\]""".toRegex().find(code)
                            if (elementMatch == null) {
                                elementMatch = """element=["'](\d+),\s*(\d+)["']""".toRegex().find(code)
                            }
                            if (elementMatch != null) {
                                json["element"] = listOf(
                                    elementMatch.groupValues[1].toInt(),
                                    elementMatch.groupValues[2].toInt()
                                )
                            }
                            json["action"] = "Tap"
                        }
                        "Type", "Type_Name" -> {
                            val textMatch = """text=["']([^"']+)["']""".toRegex().find(code)
                            if (textMatch != null) {
                                json["text"] = textMatch.groupValues[1]
                            }
                        }
                        "Swipe" -> {
                            val startMatch = """start=\[(\d+),(\d+)\]""".toRegex().find(code)
                            val endMatch = """end=\[(\d+),(\d+)\]""".toRegex().find(code)
                            if (startMatch != null && endMatch != null) {
                                json["start"] = listOf(
                                    startMatch.groupValues[1].toInt(),
                                    startMatch.groupValues[2].toInt()
                                )
                                json["end"] = listOf(
                                    endMatch.groupValues[1].toInt(),
                                    endMatch.groupValues[2].toInt()
                                )
                            }
                        }
                        "Launch" -> {
                            val appMatch = """app=["']([^"']+)["']""".toRegex().find(code)
                            if (appMatch != null) {
                                json["app"] = appMatch.groupValues[1]
                            }
                        }
                        "Ask_User", "AskUser", "ask_user" -> putOptionsArgument(json, code)
                        "Answer", "answer" -> Unit
                        "Read_Clipboard", "ReadClipboard", "read_clipboard" -> Unit
                        "Write_Clipboard", "WriteClipboard", "write_clipboard" -> Unit
                    }
                }
            }
            else -> {
                json["_metadata"] = "finish"
                json["message"] = code
            }
        }

        return json.toJsonObject()
    }

    private fun putOptionalArgument(json: MutableMap<String, Any>, code: String, key: String) {
        val match = Regex("""$key=["']([^"']+)["']""").find(code)
        if (match != null) {
            json[key] = match.groupValues[1]
        }
    }

    private fun putOptionalBooleanArgument(json: MutableMap<String, Any>, code: String, key: String) {
        val match = Regex("""$key=(true|false)""", RegexOption.IGNORE_CASE).find(code)
        if (match != null) {
            json[key] = match.groupValues[1].equals("true", ignoreCase = true)
        }
    }

    private fun putOptionsArgument(json: MutableMap<String, Any>, code: String) {
        val match = Regex("""options=\[([^\]]*)\]""").find(code) ?: return
        val options = Regex("""["']([^"']+)["']""")
            .findAll(match.groupValues[1])
            .map { it.groupValues[1] }
            .toList()
        if (options.isNotEmpty()) {
            json["options"] = options
        }
    }

    private fun Map<String, Any>.toJsonObject(): String {
        return entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${key.escapeJson()}\":${value.toJsonValue()}"
        }
    }

    private fun Any.toJsonValue(): String {
        return when (this) {
            is String -> "\"${escapeJson()}\""
            is Boolean -> toString()
            is Number -> toString()
            is List<*> -> joinToString(prefix = "[", postfix = "]") { item ->
                item?.toJsonValue() ?: "null"
            }
            else -> "\"${toString().escapeJson()}\""
        }
    }

    private fun String.escapeJson(): String {
        return buildString {
            this@escapeJson.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }
    }
}
