package com.mobileagent.phoneagent.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserInteractionRequestTest {
    @Test
    fun displayTextIncludesQuestionOptionsAndReason() {
        val text = UserInteractionRequest(
            question = "选择哪个联系人？",
            options = listOf("张三 公司", "张三 同学"),
            reason = "候选项不唯一"
        ).toDisplayText()

        assertTrue(text.contains("选择哪个联系人？"))
        assertTrue(text.contains("张三 公司 / 张三 同学"))
        assertTrue(text.contains("候选项不唯一"))
    }

    @Test
    fun answerDisplayTextIncludesAnswerAndReason() {
        val text = AnswerAction(
            answer = "推荐 A 餐厅，距离 2 公里，评分 4.8",
            reason = "查询结果已确认"
        ).toDisplayText()

        assertTrue(text.contains("推荐 A 餐厅"))
        assertTrue(text.contains("查询结果已确认"))
    }

    @Test
    fun interactionKindMapsSensitiveWireValue() {
        assertEquals(
            UserInteractionKind.SENSITIVE_CONFIRMATION,
            UserInteractionKind.fromWireValue("sensitive_confirmation")
        )
        assertEquals(UserInteractionKind.QUESTION, UserInteractionKind.fromWireValue(""))
    }

    @Test
    fun clipboardTraceDisplayTextIncludesOperationStatusAndPreview() {
        val text = ClipboardTrace(
            operation = ClipboardOperation.READ,
            success = true,
            contentPreview = "123456",
            contentLength = 6,
            reason = "获取验证码"
        ).toDisplayText()

        assertTrue(text.contains("剪贴板读取成功"))
        assertTrue(text.contains("123456"))
        assertTrue(text.contains("获取验证码"))
    }
}
