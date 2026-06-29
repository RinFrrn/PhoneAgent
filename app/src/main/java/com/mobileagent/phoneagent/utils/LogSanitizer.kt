package com.mobileagent.phoneagent.utils

object LogSanitizer {
    fun sanitize(text: String?): String {
        if (text.isNullOrBlank()) {
            return text.orEmpty()
        }

        var result = text.orEmpty()
        for ((pattern, replacement) in REGEX_REPLACEMENTS) {
            result = pattern.replace(result, replacement)
        }
        result = API_KEY_PREFIX_REGEX.replace(result) { match ->
            "${match.groupValues[1]}${maskSecret(match.groupValues[2])}"
        }
        result = SENSITIVE_FIELD_REGEX.replace(result) { match ->
            "${match.groupValues[1]}${maskSecret(match.groupValues[2])}${match.groupValues[3]}"
        }
        result = URL_SECRET_QUERY_REGEX.replace(result) { match ->
            "${match.groupValues[1]}=***"
        }
        result = PHONE_REGEX.replace(result) { match ->
            maskPhone(match.value)
        }
        return result
    }

    fun maskSecret(secret: String?): String {
        val value = secret.orEmpty()
        if (value.length < MIN_SECRET_LENGTH) {
            return "***"
        }
        val prefixLength = if (value.startsWith("sk-") || value.startsWith("glm-")) 4 else 3
        val prefix = value.take(prefixLength.coerceAtMost(value.length))
        val suffix = value.takeLast(3)
        return "$prefix***$suffix"
    }

    private fun maskPhone(phone: String): String {
        if (phone.length < 7) {
            return "***"
        }
        return "${phone.take(3)}****${phone.takeLast(4)}"
    }

    private val API_KEY_PREFIX_REGEX = Regex("""\b((?:sk-|glm-|api-))([A-Za-z0-9_-]{16,})""")
    private val SENSITIVE_FIELD_REGEX = Regex(
        pattern = """(["']?(?:api[_-]?key|apikey|secret|password|passwd|pwd|token|access[_-]?token|refresh[_-]?token|authorization|private[_-]?key)["']?\s*[:=]\s*["']?)([A-Za-z0-9._\-]{8,})(["']?)""",
        option = RegexOption.IGNORE_CASE
    )
    private val URL_SECRET_QUERY_REGEX = Regex(
        pattern = """\b(api[_-]?key|apikey|token|secret|password)=([^&\s]+)""",
        option = RegexOption.IGNORE_CASE
    )
    private val PHONE_REGEX = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")
    private val REGEX_REPLACEMENTS = listOf(
        Regex("""(?i)(Authorization\s*:\s*Bearer\s+)[^\s]+""") to "$1***",
        Regex("""(?i)(Bearer\s+)[A-Za-z0-9._\-]{12,}""") to "$1***",
        Regex("""\beyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""") to "eyJ***"
    )

    private const val MIN_SECRET_LENGTH = 8
}
