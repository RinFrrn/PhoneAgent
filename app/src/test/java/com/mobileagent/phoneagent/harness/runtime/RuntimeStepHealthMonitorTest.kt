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
}
