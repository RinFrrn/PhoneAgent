package com.mobileagent.phoneagent.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class UserInterventionMemoryFormatterTest {
    @Test
    fun includesUserAnswerWhenProvided() {
        val text = UserInterventionMemoryFormatter.format(
            message = "需要用户选择联系人",
            response = UserActionResponse(answer = "选择张三 公司")
        )

        assertTrue(text.contains("需要用户选择联系人"))
        assertTrue(text.contains("用户回答: 选择张三 公司"))
        assertTrue(text.contains("请继续执行任务"))
    }

    @Test
    fun fallsBackToHandledWhenAnswerBlank() {
        val text = UserInterventionMemoryFormatter.format(
            message = "需要用户完成登录",
            response = UserActionResponse(answer = " ")
        )

        assertTrue(text.contains("用户已完成介入操作"))
        assertTrue(text.contains("需要用户完成登录"))
    }

    @Test
    fun marksTimeoutWhenNoUserResponseArrives() {
        val text = UserInterventionMemoryFormatter.format(
            message = "需要用户确认支付",
            response = UserActionResponse.timeout()
        )

        assertTrue(text.contains("等待用户回答超时"))
        assertTrue(text.contains("再次使用 Ask_User"))
        assertTrue(text.contains("需要用户确认支付"))
    }
}
