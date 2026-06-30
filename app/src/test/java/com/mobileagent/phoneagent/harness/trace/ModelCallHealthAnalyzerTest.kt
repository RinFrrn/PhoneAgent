package com.mobileagent.phoneagent.harness.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCallHealthAnalyzerTest {
    @Test
    fun emptySummaryReturnsNoData() {
        val report = ModelCallHealthAnalyzer.analyze(
            ModelCallSummary(
                callCount = 0,
                totalLatencyMs = 0,
                averageLatencyMs = 0,
                requestChars = 0,
                responseChars = 0
            )
        )

        assertEquals(ModelCallHealthLevel.NO_DATA, report.level)
        assertTrue(report.isEmpty())
    }

    @Test
    fun healthySummaryHasNoWarnings() {
        val report = ModelCallHealthAnalyzer.analyze(
            ModelCallSummary(
                callCount = 2,
                totalLatencyMs = 1_000,
                averageLatencyMs = 500,
                requestChars = 3_000,
                responseChars = 600,
                totalTokens = 300
            )
        )

        assertEquals(ModelCallHealthLevel.HEALTHY, report.level)
        assertTrue(report.warnings.isEmpty())
    }

    @Test
    fun slowAverageLatencyReturnsSlowHealth() {
        val report = ModelCallHealthAnalyzer.analyze(
            ModelCallSummary(
                callCount = 2,
                totalLatencyMs = 24_000,
                averageLatencyMs = 12_000,
                requestChars = 4_000,
                responseChars = 600,
                totalTokens = 300
            )
        )

        assertEquals(ModelCallHealthLevel.SLOW, report.level)
        assertTrue(report.toDisplayText().contains("平均延迟"))
        assertTrue(report.recommendation.contains("响应偏慢"))
    }

    @Test
    fun hugeContextReturnsHeavyHealth() {
        val report = ModelCallHealthAnalyzer.analyze(
            ModelCallSummary(
                callCount = 1,
                totalLatencyMs = 3_000,
                averageLatencyMs = 3_000,
                requestChars = 130_000,
                responseChars = 31_000,
                totalTokens = 8_000
            )
        )

        assertEquals(ModelCallHealthLevel.HEAVY, report.level)
        assertTrue(report.recommendation.contains("上下文"))
    }

    @Test
    fun missingTokenUsageReturnsWatchHealth() {
        val report = ModelCallHealthAnalyzer.analyze(
            ModelCallSummary(
                callCount = 1,
                totalLatencyMs = 100,
                averageLatencyMs = 100,
                requestChars = 500,
                responseChars = 100
            )
        )

        assertEquals(ModelCallHealthLevel.WATCH, report.level)
        assertTrue(report.toDisplayText().contains("token usage"))
    }
}
