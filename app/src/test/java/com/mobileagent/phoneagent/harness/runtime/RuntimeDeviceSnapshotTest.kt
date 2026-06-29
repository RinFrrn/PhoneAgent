package com.mobileagent.phoneagent.harness.runtime

import com.mobileagent.phoneagent.agent.Mode
import com.mobileagent.phoneagent.harness.act.ExecutionHumanizationProfile
import com.mobileagent.phoneagent.harness.trace.ModelCallSummary
import com.mobileagent.phoneagent.harness.trace.RecentTaskPerformanceSummary
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDeviceSnapshotTest {
    @Test
    fun displayTextIncludesDeviceSystemScreenAndBattery() {
        val snapshot = RuntimeDeviceSnapshot(
            manufacturer = "Google",
            model = "Pixel 8",
            androidVersion = "15",
            sdkInt = 35,
            screenResolution = "1080x2400",
            batteryPercent = 88,
            charging = true
        )

        val text = snapshot.toDisplayText()

        assertTrue(text.contains("Google Pixel 8"))
        assertTrue(text.contains("Android 15"))
        assertTrue(text.contains("1080x2400"))
        assertTrue(text.contains("88%"))
        assertTrue(text.contains("充电中"))
    }

    @Test
    fun lowBatteryWarnsOnlyWhenNotCharging() {
        val low = RuntimeDeviceSnapshot(
            manufacturer = "Google",
            model = "Pixel",
            androidVersion = "15",
            sdkInt = 35,
            screenResolution = "1080x2400",
            batteryPercent = 12,
            charging = false
        )
        val charging = low.copy(charging = true)

        assertTrue(low.lowBatteryWarning()?.contains("12%") == true)
        assertNull(charging.lowBatteryWarning())
    }

    @Test
    fun diagnosticDetailIncludesDeviceSnapshot() {
        val diagnostic = RuntimeDiagnosticSnapshotBuilder.build(
            running = false,
            mode = Mode.HYBRID,
            modelLabel = "GLM · glm",
            readiness = RunReadinessChecker.evaluate(
                modelConfigured = true,
                accessibilityEnabled = true,
                overlayEnabled = true,
                notificationEnabled = true,
                mode = Mode.HYBRID,
                screenCaptureReady = true,
                humanizationEnabled = false
            ),
            humanizationProfile = ExecutionHumanizationProfile(),
            recentSummary = RecentTaskPerformanceSummary(
                taskCount = 0,
                finishedCount = 0,
                successCount = 0,
                failedCount = 0,
                stoppedCount = 0,
                runningCount = 0,
                totalSteps = 0,
                modelCallSummary = ModelCallSummary(
                    callCount = 0,
                    totalLatencyMs = 0,
                    averageLatencyMs = 0,
                    requestChars = 0,
                    responseChars = 0
                )
            ),
            history = emptyList(),
            deviceSnapshot = RuntimeDeviceSnapshot(
                manufacturer = "Google",
                model = "Pixel 8",
                androidVersion = "15",
                sdkInt = 35,
                screenResolution = "1080x2400",
                batteryPercent = 10,
                charging = false
            ),
            generatedAt = 1L
        )

        val detail = diagnostic.detailText()

        assertTrue(detail.contains("设备: Google Pixel 8"))
        assertTrue(detail.contains("设备提示"))
        assertTrue(diagnostic.compactText().contains("低电量"))
    }
}
