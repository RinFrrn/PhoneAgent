package com.mobileagent.phoneagent.harness.verify

import com.mobileagent.phoneagent.action.TapAction
import com.mobileagent.phoneagent.action.WaitAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVerificationRulesTest {
    @Test
    fun tapIntoPaymentPasswordScreenRequiresUserConfirmation() {
        val result = AppVerificationRules.detectSensitiveCheckpoint(
            action = TapAction(x = 500, y = 900, message = "点击支付"),
            currentApp = "支付宝",
            visibleText = "请输入支付密码 确认付款 忘记密码"
        )

        requireNotNull(result)
        assertFalse(result.passed)
        assertTrue(result.reason.contains("敏感确认页面"))
        assertTrue(result.reason.contains("支付密码"))
    }

    @Test
    fun nonInteractiveWaitDoesNotTriggerSensitiveCheckpoint() {
        val result = AppVerificationRules.detectSensitiveCheckpoint(
            action = WaitAction(durationMs = 1000),
            currentApp = "短信",
            visibleText = "验证码 123456"
        )

        assertNull(result)
    }

    @Test
    fun sensitiveGoalTextAloneDoesNotTriggerCheckpoint() {
        val result = AppVerificationRules.detectSensitiveCheckpoint(
            action = TapAction(x = 500, y = 500, message = "打开联系人"),
            currentApp = "支付宝",
            visibleText = "首页 转账 收付款 我的"
        )

        assertNull(result)
    }
}
