package com.mobileagent.phoneagent.harness.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStepHealthMonitorTest {
    @Test
    fun warnsWhenLongTaskReachesTenSteps() {
        val warnings = RuntimeStepHealthMonitor.warningsForStep(
            stepIndex = 10,
            maxSteps = Int.MAX_VALUE
        )

        assertEquals(listOf("long_task"), warnings.map { it.id })
        assertEquals(RuntimeWarningSeverity.WARNING, warnings.first().severity)
    }

    @Test
    fun warnsWhenVeryLongTaskReachesThirtySteps() {
        val warnings = RuntimeStepHealthMonitor.warningsForStep(
            stepIndex = 30,
            maxSteps = Int.MAX_VALUE
        )

        assertEquals(listOf("very_long_task"), warnings.map { it.id })
        assertEquals(RuntimeWarningSeverity.CRITICAL, warnings.first().severity)
    }

    @Test
    fun warnsWhenFiniteStepBudgetCrossesThresholds() {
        val warningStep = RuntimeStepHealthMonitor.warningsForStep(
            stepIndex = 21,
            maxSteps = 30
        )
        val criticalStep = RuntimeStepHealthMonitor.warningsForStep(
            stepIndex = 27,
            maxSteps = 30
        )

        assertTrue(warningStep.any { it.id == "max_steps_warning" })
        assertTrue(criticalStep.any { it.id == "max_steps_critical" })
    }

    @Test
    fun doesNotRepeatPercentWarningsAfterThresholdCrossing() {
        val warnings = RuntimeStepHealthMonitor.warningsForStep(
            stepIndex = 22,
            maxSteps = 30
        )

        assertTrue(warnings.none { it.id == "max_steps_warning" })
    }

    @Test
    fun timingWarningsFlagSlowAndCriticalSteps() {
        val slow = RuntimeStepTiming(totalMs = 15_000L).warnings()
        val critical = RuntimeStepTiming(totalMs = 30_000L).warnings()

        assertEquals(listOf("slow_step"), slow.map { it.id })
        assertEquals(RuntimeWarningSeverity.WARNING, slow.first().severity)
        assertEquals(listOf("slow_step_critical"), critical.map { it.id })
        assertEquals(RuntimeWarningSeverity.CRITICAL, critical.first().severity)
    }

    @Test
    fun timingDisplayIncludesPhaseBreakdown() {
        val text = RuntimeStepTiming(
            totalMs = 1234L,
            observationMs = 100L,
            planningMs = 200L,
            executionMs = 300L,
            verificationMs = 400L
        ).toDisplayText()

        assertTrue(text.contains("总耗时 1234ms"))
        assertTrue(text.contains("观察 100ms"))
        assertTrue(text.contains("规划 200ms"))
        assertTrue(text.contains("执行 300ms"))
        assertTrue(text.contains("验证 400ms"))
    }

    @Test
    fun contextBudgetWarningsAppearWhenEstimatedUsageCrossesThresholds() {
        val warningStep = RuntimeStepHealthMonitor.warningsForStep(
            stepIndex = 22,
            maxSteps = Int.MAX_VALUE
        )
        val criticalStep = RuntimeStepHealthMonitor.warningsForStep(
            stepIndex = 32,
            maxSteps = Int.MAX_VALUE
        )

        assertTrue(warningStep.any { it.id == "context_budget_warning" })
        assertTrue(criticalStep.any { it.id == "context_budget_critical" })
    }

    @Test
    fun contextBudgetWarningsDoNotRepeatAfterThresholdCrossing() {
        val warnings = RuntimeStepHealthMonitor.warningsForStep(
            stepIndex = 23,
            maxSteps = Int.MAX_VALUE
        )

        assertTrue(warnings.none { it.id == "context_budget_warning" })
    }
}
