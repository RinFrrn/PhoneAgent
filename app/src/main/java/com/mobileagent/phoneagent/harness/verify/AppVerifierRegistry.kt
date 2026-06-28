package com.mobileagent.phoneagent.harness.verify

import android.content.Context
import com.mobileagent.phoneagent.action.ActionParser
import com.mobileagent.phoneagent.action.BackAction
import com.mobileagent.phoneagent.action.LaunchAction
import com.mobileagent.phoneagent.action.TapAction
import com.mobileagent.phoneagent.harness.act.ExecutionResult
import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.spec.TaskSpec
import com.mobileagent.phoneagent.skill.SkillRegistry

class AppAwareStepVerifier(
    private val context: Context,
    private val genericVerifier: StepVerifier = GenericStepVerifier(),
    private val actionParser: ActionParser = ActionParser(),
    private val parsedActionProvider: ((String) -> Any?)? = null
) : StepVerifier {
    override fun verify(
        before: Observation,
        execution: ExecutionResult,
        after: Observation?,
        taskSpec: TaskSpec
    ): VerificationResult {
        val base = genericVerifier.verify(before, execution, after, taskSpec)
        if (execution.launchTrace?.targetPackage != null) {
            return base
        }
        val matchedSkill = SkillRegistry.matchingSkills(
            context,
            after?.currentApp ?: before.currentApp,
            taskSpec.goal
        ).firstOrNull() ?: return base
        val action = parsedActionProvider?.invoke(execution.actionJson)
            ?: runCatching { actionParser.parse(execution.actionJson) }.getOrNull()
            ?: return base
        val afterText = after.textDigest()

        return when (matchedSkill.id) {
            "wechat" -> verifyWechat(base, action, before, after, afterText)
            "ecommerce", "food_delivery" -> verifyCommerce(base, action, before, after, afterText)
            "xiaohongshu", "douyin" -> verifyContentApp(base, action, before, after, afterText)
            else -> base
        }
    }

    private fun verifyWechat(
        base: VerificationResult,
        action: Any,
        before: Observation,
        after: Observation?,
        afterText: String
    ): VerificationResult {
        if (after == null) return base
        return when (action) {
            is LaunchAction -> if (containsAny(after.currentApp.orEmpty(), listOf("微信", "wechat"))) {
                VerificationResult(true, 0.98f, "微信应用已启动", after.currentApp)
            } else {
                base
            }
            is BackAction, is TapAction -> if (hasMeaningfulChange(before, after)) {
                VerificationResult(true, 0.82f, "微信页面发生变化，进入有效状态", afterText.take(100))
            } else {
                base
            }
            else -> base
        }
    }

    private fun verifyCommerce(
        base: VerificationResult,
        action: Any,
        before: Observation,
        after: Observation?,
        afterText: String
    ): VerificationResult {
        if (after == null) return base
        return when (action) {
            is LaunchAction -> if (containsAny(after.currentApp.orEmpty(), listOf("淘宝", "京东", "拼多多", "美团", "饿了么"))) {
                VerificationResult(true, 0.98f, "目标商业应用已启动", after.currentApp)
            } else {
                base
            }
            is TapAction -> if (hasMeaningfulChange(before, after)) {
                VerificationResult(true, 0.84f, "商业页面发生变化，进入有效状态", afterText.take(100))
            } else {
                base
            }
            else -> base
        }
    }

    private fun verifyContentApp(
        base: VerificationResult,
        action: Any,
        before: Observation,
        after: Observation?,
        afterText: String
    ): VerificationResult {
        if (after == null) return base
        return when (action) {
            is LaunchAction -> if (containsAny(after.currentApp.orEmpty(), listOf("抖音", "小红书", "red", "douyin"))) {
                VerificationResult(true, 0.98f, "内容应用已启动", after.currentApp)
            } else {
                base
            }
            is TapAction, is BackAction -> if (hasMeaningfulChange(before, after)) {
                VerificationResult(true, 0.8f, "内容应用页面发生变化，进入有效状态", afterText.take(100))
            } else {
                base
            }
            else -> base
        }
    }

    private fun Observation?.textDigest(): String {
        if (this == null) return ""
        return contentItems
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("\n")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun hasMeaningfulChange(before: Observation, after: Observation): Boolean {
        return before.currentPackage != after.currentPackage ||
            before.currentApp != after.currentApp ||
            before.textDigest() != after.textDigest()
    }

    private fun containsAny(text: String, tokens: List<String>): Boolean {
        val normalized = text.lowercase()
        return tokens.any { normalized.contains(it.lowercase()) }
    }
}
