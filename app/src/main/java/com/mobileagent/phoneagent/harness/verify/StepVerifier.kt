package com.mobileagent.phoneagent.harness.verify

import com.mobileagent.phoneagent.action.ActionParser
import com.mobileagent.phoneagent.action.BackAction
import com.mobileagent.phoneagent.action.DragAction
import com.mobileagent.phoneagent.action.FinishAction
import com.mobileagent.phoneagent.action.HomeAction
import com.mobileagent.phoneagent.action.KeyEventAction
import com.mobileagent.phoneagent.action.LaunchAction
import com.mobileagent.phoneagent.action.RecentAppsAction
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

        if (execution.requiresTakeover || execution.userInteractionRequest != null) {
            return VerificationResult(
                passed = true,
                confidence = 1.0f,
                reason = execution.userInteractionRequest?.let {
                    "等待用户回答: ${it.question}"
                } ?: "等待用户介入，无需页面变化验证"
            )
        }

        execution.clipboardTrace?.let { trace ->
            return VerificationResult(
                passed = trace.success,
                confidence = 1.0f,
                reason = trace.toDisplayText()
            )
        }

        if (after == null || after.failureMessage != null) {
            return VerificationResult(
                passed = false,
                confidence = 0.8f,
                reason = after?.failureMessage ?: "执行后无法重新采集页面状态"
            )
        }

        if (execution.launchTrace != null) {
            return verifyLaunch(before, execution, after)
        }

        val action = runCatching { actionParser.parse(execution.actionJson) }.getOrNull()
        if (action == null) {
            return when (fallbackActionType(execution.actionJson)?.lowercase()) {
                "tap", "click" -> verifyTap(before, after)
                "swipe", "scroll" -> verifyScrollableChange(before, after)
                "drag" -> verifyGestureChange(before, after, "拖拽动作")
                "type", "type_name", "input_text" -> verifyType(before, after)
                "launch", "launch_app" -> verifyLaunch(before, execution, after)
                "back" -> verifyNavigationChange(before, after, "返回动作")
                "home" -> verifyNavigationChange(before, after, "主页动作")
                "press_key" -> verifyPressKeyFallback(before, after, execution.actionJson)
                "key_event", "keyevent" -> verifyKeyEventFallback(before, after, execution.actionJson)
                else -> VerificationResult(
                    passed = true,
                    confidence = 0.3f,
                    reason = "无法解析动作类型，跳过验证"
                )
            }
        }

        return when (action) {
            is FinishAction -> VerificationResult(true, 1.0f, "任务显式结束")
            is WaitAction -> VerificationResult(true, 0.9f, "等待动作无需页面变化验证")
            is TypeAction -> verifyType(before, after)
            is LaunchAction -> verifyLaunch(before, execution, after)
            is HomeAction -> verifyNavigationChange(before, after, "主页动作")
            is BackAction -> verifyNavigationChange(before, after, "返回动作")
            is RecentAppsAction -> verifyNavigationChange(before, after, "最近任务动作")
            is KeyEventAction -> verifyNavigationChange(before, after, "系统按键事件")
            is SwipeAction -> verifyScrollableChange(before, after)
            is DragAction -> verifyGestureChange(before, after, "拖拽动作")
            is TapAction -> verifyTap(before, after)
            else -> verifyGenericChange(before, after)
        }
    }

    private fun verifyLaunch(
        before: Observation,
        execution: ExecutionResult,
        after: Observation
    ): VerificationResult {
        val launchTrace = execution.launchTrace
        if (launchTrace != null) {
            val expectedPackage = launchTrace.targetPackage
            val observedPackage = after.currentPackage ?: launchTrace.afterPackage
            if (expectedPackage != null && observedPackage == expectedPackage) {
                return VerificationResult(
                    true,
                    0.98f,
                    "目标包名已到达",
                    observedChange = "${before.currentPackage ?: before.currentApp} -> $observedPackage"
                )
            }
            if (expectedPackage != null) {
                return VerificationResult(
                    false,
                    0.9f,
                    "启动后未到达目标包名: expected=$expectedPackage actual=${observedPackage ?: "未知"} strategy=${launchTrace.strategy}",
                    observedChange = "${before.currentPackage ?: before.currentApp} -> ${observedPackage ?: after.currentApp}"
                )
            }
        }

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

    private fun verifyGestureChange(before: Observation, after: Observation, label: String): VerificationResult {
        if (before.currentPackage != after.currentPackage || before.currentApp != after.currentApp) {
            return VerificationResult(
                true,
                0.85f,
                "$label 后应用上下文变化",
                "${before.currentPackage ?: before.currentApp} -> ${after.currentPackage ?: after.currentApp}"
            )
        }

        val beforeText = before.textDigest()
        val afterText = after.textDigest()
        return if (beforeText != afterText) {
            VerificationResult(true, 0.72f, "$label 后页面内容变化", summarizeDiff(beforeText, afterText))
        } else {
            VerificationResult(false, 0.68f, "$label 后页面内容未明显变化")
        }
    }

    private fun verifyTap(before: Observation, after: Observation): VerificationResult {
        if (before.currentPackage != after.currentPackage || before.currentApp != after.currentApp) {
            return VerificationResult(
                true,
                0.9f,
                "点击后应用发生变化",
                "${before.currentPackage ?: before.currentApp} -> ${after.currentPackage ?: after.currentApp}"
            )
        }

        val beforeText = before.textDigest()
        val afterText = after.textDigest()
        return if (beforeText != afterText) {
            VerificationResult(true, 0.75f, "点击后页面内容变化", summarizeDiff(beforeText, afterText))
        } else {
            VerificationResult(false, 0.85f, "点击手势已调度，但页面内容和当前应用均未变化")
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

    private fun fallbackActionType(actionJson: String): String? {
        return """"action"\s*:\s*"([^"]+)"""".toRegex()
            .find(actionJson)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun fallbackActionKey(actionJson: String): String? {
        return """"key"\s*:\s*"([^"]+)"""".toRegex()
            .find(actionJson)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun verifyPressKeyFallback(before: Observation, after: Observation, actionJson: String): VerificationResult {
        return when (fallbackActionKey(actionJson)?.normalizedKey()) {
            "back" -> verifyNavigationChange(before, after, "返回动作")
            "home" -> verifyNavigationChange(before, after, "主页动作")
            "recent", "recents", "overview" -> verifyNavigationChange(before, after, "最近任务动作")
            else -> verifyGenericChange(before, after)
        }
    }

    private fun verifyKeyEventFallback(before: Observation, after: Observation, actionJson: String): VerificationResult {
        return when (fallbackActionKey(actionJson)?.normalizedKey()) {
            "back" -> verifyNavigationChange(before, after, "返回动作")
            "home" -> verifyNavigationChange(before, after, "主页动作")
            "recent", "recents", "overview" -> verifyNavigationChange(before, after, "最近任务动作")
            else -> verifyNavigationChange(before, after, "系统按键事件")
        }
    }

    private fun String.normalizedKey(): String {
        return trim().replace("-", "_").lowercase()
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
