package com.mobileagent.phoneagent.harness.runtime

import com.mobileagent.phoneagent.agent.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunReadinessCheckerTest {
    @Test
    fun missingRequiredRuntimePiecesBecomeBlockers() {
        val report = RunReadinessChecker.evaluate(
            modelConfigured = false,
            accessibilityEnabled = false,
            overlayEnabled = false,
            notificationEnabled = false,
            mode = Mode.ACCESSIBILITY,
            screenCaptureReady = true,
            humanizationEnabled = false
        )

        assertFalse(report.ready)
        assertEquals(
            listOf("model", "accessibility", "overlay", "notification"),
            report.blockers.map { it.id }
        )
        assertEquals("需要完成运行前检查", report.statusTitle(running = false))
        assertTrue(report.statusDetail(running = false).contains("模型未配置"))
    }

    @Test
    fun visualModeWithoutScreenCaptureIsWarningNotBlocker() {
        val report = RunReadinessChecker.evaluate(
            modelConfigured = true,
            accessibilityEnabled = true,
            overlayEnabled = true,
            notificationEnabled = true,
            mode = Mode.HYBRID,
            screenCaptureReady = false,
            humanizationEnabled = false
        )

        assertTrue(report.ready)
        assertEquals(listOf("screen_capture"), report.warnings.map { it.id })
        assertEquals("基本就绪", report.statusTitle(running = false))
    }

    @Test
    fun humanizationEnabledIsReportedAsInfo() {
        val report = RunReadinessChecker.evaluate(
            modelConfigured = true,
            accessibilityEnabled = true,
            overlayEnabled = true,
            notificationEnabled = true,
            mode = Mode.ACCESSIBILITY,
            screenCaptureReady = true,
            humanizationEnabled = true
        )

        assertTrue(report.ready)
        assertEquals(listOf("humanization"), report.infos.map { it.id })
        assertEquals("已就绪", report.statusTitle(running = false))
    }

    @Test
    fun lockedOrSleepingDeviceBecomesRuntimeBlocker() {
        val report = RunReadinessChecker.evaluate(
            modelConfigured = true,
            accessibilityEnabled = true,
            overlayEnabled = true,
            notificationEnabled = true,
            mode = Mode.ACCESSIBILITY,
            screenCaptureReady = true,
            humanizationEnabled = false,
            deviceSnapshot = RuntimeDeviceSnapshot(
                manufacturer = "Google",
                model = "Pixel",
                androidVersion = "15",
                sdkInt = 35,
                screenResolution = "1080x2400",
                interactive = false,
                keyguardLocked = true,
                powerSaveMode = false
            )
        )

        assertFalse(report.ready)
        assertEquals(
            listOf("device_blocker_1", "device_blocker_2"),
            report.blockers.map { it.id }
        )
        assertTrue(report.statusDetail(running = false).contains("设备状态不可执行"))
    }

    @Test
    fun lowBatteryAndPowerSaveBecomeDeviceWarnings() {
        val report = RunReadinessChecker.evaluate(
            modelConfigured = true,
            accessibilityEnabled = true,
            overlayEnabled = true,
            notificationEnabled = true,
            mode = Mode.ACCESSIBILITY,
            screenCaptureReady = true,
            humanizationEnabled = false,
            deviceSnapshot = RuntimeDeviceSnapshot(
                manufacturer = "Google",
                model = "Pixel",
                androidVersion = "15",
                sdkInt = 35,
                screenResolution = "1080x2400",
                batteryPercent = 10,
                charging = false,
                interactive = true,
                keyguardLocked = false,
                powerSaveMode = true
            )
        )

        assertTrue(report.ready)
        assertEquals(
            listOf("device_warning_1", "device_warning_2"),
            report.warnings.map { it.id }
        )
        assertTrue(report.statusDetail(running = false).contains("10%"))
    }
}
