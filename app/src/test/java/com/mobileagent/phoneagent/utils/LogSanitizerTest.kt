package com.mobileagent.phoneagent.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSanitizerTest {
    @Test
    fun masksApiKeysBearerTokensAndUrlSecrets() {
        val raw = """
            api_key=sk-1234567890abcdefghijklmnopqrstuvwxyz
            Authorization: Bearer eyJvery.secret.token
            https://example.com?token=abcdef1234567890&model=gpt
        """.trimIndent()

        val sanitized = LogSanitizer.sanitize(raw)

        assertFalse(sanitized.contains("1234567890abcdefghijklmnopqrstuvwxyz"))
        assertFalse(sanitized.contains("abcdef1234567890"))
        assertTrue(sanitized.contains("api_key="))
        assertTrue(sanitized.contains("***"))
        assertTrue(sanitized.contains("Authorization: Bearer ***"))
        assertTrue(sanitized.contains("token=***"))
    }

    @Test
    fun masksChinesePhoneNumbers() {
        val sanitized = LogSanitizer.sanitize("联系人手机号 13812345678")

        assertFalse(sanitized.contains("13812345678"))
        assertTrue(sanitized.contains("138****5678"))
    }
}
