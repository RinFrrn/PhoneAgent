package com.mobileagent.phoneagent.harness.verify

import com.mobileagent.phoneagent.action.ActionParser
import com.mobileagent.phoneagent.action.BackAction
import com.mobileagent.phoneagent.action.FinishAction
import com.mobileagent.phoneagent.action.HomeAction
import com.mobileagent.phoneagent.action.LaunchAction
import com.mobileagent.phoneagent.action.SwipeAction
import com.mobileagent.phoneagent.action.TapAction
import com.mobileagent.phoneagent.action.TypeAction
import com.mobileagent.phoneagent.action.WaitAction
import com.mobileagent.phoneagent.harness.act.ExecutionResult
import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.spec.TaskSpec
import kotlin.math.min

interface StepVerifier {
    fun verify(
        before: Observation,
        execution: ExecutionResult,
        after: Observation?,
        taskSpec: TaskSpec
    ): VerificationResult
}

class GenericStepVerifier(
    private val actionParser: ActionParser = ActionParser()
) : StepVerifier {
    override fun verify(
        before: Observation,
        execution: ExecutionResult,
        after: Observation?,
        taskSpec: TaskSpec
    ): VerificationResult {
        if (!execution.success) {
            return VerificationResult(
                passed = false,
                confidence = 1.0f,
                reason = execution.message ?: "动作执行失败"
            )
        }

        if (execution.shouldFinish) {
            return VerificationResult(
                passed = true,
                confidence = 1.0f,
                reason = "任务显式结束"
            )
        }

        val action = runCatching { actionParser.parse(execution.actionJson) }.getOrNull()
        if (action == null) {
            return VerificationResult(
                passed = true,
                confidence = 0.3f,
                reason = "无法解析动作类型，跳过验证"
            )
        }

        if (after == null || after.failureMessage != null) {
            return VerificationResult(
                passed = false,
                confidence = 0.8f,
                reason = after?.failureMessage ?: "执行后无法重新采集页面状态"
            )
        }

        return when (action) {
            is FinishAction -> VerificationResult(true, 1.0f, "任务显式结束")
            is WaitAction -> VerificationResult(true, 0.9f, "等待动作无需页面变化验证")
            is TypeAction -> verifyType(before, after)
            is LaunchAction -> verifyLaunch(before, after)
            is HomeAction -> verifyNavigationChange(before, after, "主页动作")
            is BackAction -> verifyNavigationChange(before, after, "返回动作")
            is SwipeAction -> verifyScrollableChange(before, after)
            is TapAction -> verifyTap(before, after)
            else -> verifyGenericChange(before, after)
        }
    }

    private fun verifyLaunch(before: Observation, after: Observation): VerificationResult {
        val beforeApp = before.currentApp.orEmpty()
        val afterApp = after.currentApp.orEmpty()
        return if (afterApp.isNotBlank() && afterApp != beforeApp) {
            VerificationResult(true, 0.95f, "应用切换成功", observedChange = "$beforeApp -> $afterApp")
        } else {
            VerificationResult(false, 0.8f, "启动后当前应用未变化", observedChange = afterApp)
        }
    }

    private fun verifyNavigationChange(before: Observation, after: Observation, label: String): VerificationResult {
        if (before.currentApp != after.currentApp) {
            return VerificationResult(true, 0.9f, "$label 后当前应用发生变化", "${before.currentApp} -> ${after.currentApp}")
        }

        val beforeText = before.textDigest()
        val afterText = after.textDigest()
        return if (beforeText != afterText) {
            VerificationResult(true, 0.7f, "$label 后页面内容变化", summarizeDiff(beforeText, afterText))
        } else {
            VerificationResult(false, 0.65f, "$label 后页面内容未明显变化")
        }
    }

    private fun verifyScrollableChange(before: Observation, after: Observation): VerificationResult {
        val beforeText = before.textDigest()
        val afterText = after.textDigest()
        return if (beforeText != afterText) {
            VerificationResult(true, 0.8f, "滑动后页面内容变化", summarizeDiff(beforeText, afterText))
        } else {
            VerificationResult(false, 0.7f, "滑动后页面内容未变化")
        }
    }

    private fun verifyTap(before: Observation, after: Observation): VerificationResult {
        if (before.currentApp != after.currentApp) {
            return VerificationResult(true, 0.9f, "点击后应用发生变化", "${before.currentApp} -> ${after.currentApp}")
        }

        val beforeText = before.textDigest()
        val afterText = after.textDigest()
        return if (beforeText != afterText) {
            VerificationResult(true, 0.75f, "点击后页面内容变化", summarizeDiff(beforeText, afterText))
        } else {
            VerificationResult(false, 0.6f, "点击后页面内容未变化")
        }
    }

    private fun verifyType(before: Observation, after: Observation): VerificationResult {
        val beforeText = before.textDigest()
        val afterText = after.textDigest()
        return if (beforeText != afterText) {
            VerificationResult(true, 0.8f, "输入后页面文本变化", summarizeDiff(beforeText, afterText))
        } else {
            VerificationResult(false, 0.7f, "输入后未观察到文本变化")
        }
    }

    private fun verifyGenericChange(before: Observation, after: Observation): VerificationResult {
        if (before.currentApp != after.currentApp) {
            return VerificationResult(true, 0.8f, "动作后当前应用变化", "${before.currentApp} -> ${after.currentApp}")
        }

        val beforeText = before.textDigest()
        val afterText = after.textDigest()
        return if (beforeText != afterText) {
            VerificationResult(true, 0.65f, "动作后页面内容变化", summarizeDiff(beforeText, afterText))
        } else {
            VerificationResult(true, 0.25f, "动作后页面变化不明显，暂不判定失败")
        }
    }

    private fun Observation.textDigest(): String {
        return contentItems
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("\n")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun summarizeDiff(before: String, after: String): String {
        if (before == after) {
            return "无变化"
        }
        val beforePreview = before.take(80)
        val afterPreview = after.take(80)
        return "before=${beforePreview.ifBlank { "<empty>" }} | after=${afterPreview.ifBlank { "<empty>" }}"
    }
}
