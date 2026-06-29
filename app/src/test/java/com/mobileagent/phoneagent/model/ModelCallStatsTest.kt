package com.mobileagent.phoneagent.model

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCallStatsTest {
    @Test
    fun openAiUsageIsCopiedIntoStats() {
        val json = JsonParser.parseString(
            """
            {
              "usage": {
                "prompt_tokens": 12,
                "completion_tokens": 7,
                "total_tokens": 19
              }
            }
            """.trimIndent()
        ).asJsonObject

        val stats = baseStats().withUsage(json)

        assertEquals(12, stats.promptTokens)
        assertEquals(7, stats.completionTokens)
        assertEquals(19, stats.totalTokens)
    }

    @Test
    fun googleUsageMetadataIsCopiedIntoStats() {
        val json = JsonParser.parseString(
            """
            {
              "usageMetadata": {
                "promptTokenCount": 20,
                "candidatesTokenCount": 5,
                "totalTokenCount": 25
              }
            }
            """.trimIndent()
        ).asJsonObject

        val stats = baseStats().withUsage(json)

        assertEquals(20, stats.promptTokens)
        assertEquals(5, stats.completionTokens)
        assertEquals(25, stats.totalTokens)
    }

    @Test
    fun missingUsageKeepsTokenCountsUnknown() {
        val json = JsonParser.parseString("""{"choices":[]}""").asJsonObject

        val stats = baseStats().withUsage(json)

        assertNull(stats.promptTokens)
        assertNull(stats.completionTokens)
        assertNull(stats.totalTokens)
        assertTrue(stats.summary().contains("tokens=unknown"))
    }

    private fun baseStats(): ModelCallStats {
        return ModelCallStats(
            providerName = "OPENAI",
            modelName = "gpt-test",
            latencyMs = 123,
            requestChars = 456,
            responseChars = 789
        )
    }
}
