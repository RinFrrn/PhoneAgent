package com.mobileagent.phoneagent.agent

import android.util.Log
import com.mobileagent.phoneagent.action.ActionResult
import com.mobileagent.phoneagent.model.Message

class FailureTracker {
    private val tag = "FailureTracker"

    var consecutiveFailures: Int = 0
        private set

    var lastFailedAction: String? = null
        private set

    fun reset() {
        consecutiveFailures = 0
        lastFailedAction = null
    }

    fun recordActionResult(actionJson: String, actionResult: ActionResult) {
        if (!actionResult.success) {
            consecutiveFailures++
            lastFailedAction = actionJson
            Log.w(tag, "操作失败，连续失败次数: $consecutiveFailures")
        } else {
            reset()
        }
    }

    fun consumeReplanPrompt(task: String?): Message? {
        if (consecutiveFailures < 2) {
            return null
        }

        val prompt = Message(
            "user",
            "** ⚠️ 重要提示：需要重新规划策略 **\n\n" +
                "你已经连续失败了 $consecutiveFailures 次。当前方法不可行，请立即尝试完全不同的方法：\n\n" +
                "1. **重新分析任务**：任务目标是 '$task'，请确认是否理解正确\n" +
                "2. **尝试不同操作**：\n" +
                "   - 如果点击失败，尝试调整坐标位置、尝试滑动、或使用 Launch 启动应用\n" +
                "   - 如果找不到元素，尝试滑动屏幕查看更多内容、返回上一页、或重新搜索\n" +
                "   - 如果操作无效，尝试返回后重新进入、等待页面加载、或使用不同的操作方式\n" +
                "3. **检查当前状态**：仔细分析屏幕截图，确认当前处于什么页面、什么状态\n" +
                "4. **不要放弃**：继续尝试不同的方法，直到任务完成\n" +
                "5. **如果确实无法完成**：使用 finish(message=\"无法完成的原因\") 说明情况\n\n" +
                "请立即重新分析屏幕，制定新的操作计划，并继续执行。"
        )

        consecutiveFailures = 0
        return prompt
    }

    fun maybeUserInterventionPrompt(): Message? {
        if (consecutiveFailures < 5) {
            return null
        }

        return Message(
            "user",
            "** ⚠️ 需要用户介入 **\n\n" +
                "已经连续失败 $consecutiveFailures 次，当前方法似乎无法完成任务。\n" +
                "如果遇到以下情况，请使用 Take_over 请求用户介入：\n" +
                "1. 需要输入验证码、密码等安全信息\n" +
                "2. 需要用户手动选择或确认\n" +
                "3. 遇到无法自动处理的情况\n\n" +
                "否则，请继续尝试不同的方法完成任务。"
        )
    }

    fun maybeReplanHintForExecution(): String? {
        if (consecutiveFailures >= 2 && lastFailedAction != null) {
            return lastFailedAction
        }
        return null
    }
}
