package com.mobileagent.phoneagent.harness.verify

import android.content.Context
import com.mobileagent.phoneagent.action.ActionParser
import com.mobileagent.phoneagent.action.BackAction
import com.mobileagent.phoneagent.action.LaunchAction
import com.mobileagent.phoneagent.action.TapAction
import com.mobileagent.phoneagent.action.TypeAction
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
        val action = parsedActionProvider?.invoke(execution.actionJson)
            ?: runCatching { actionParser.parse(execution.actionJson) }.getOrNull()
            ?: return base
        val afterText = after.textDigest()
        AppVerificationRules.detectSensitiveCheckpoint(
            action = action,
            currentApp = after?.currentApp ?: before.currentApp,
            taskGoal = taskSpec.goal,
            visibleText = afterText
        )?.let { return it }

        val matchedSkill = SkillRegistry.matchingSkills(
            context,
            after?.currentApp ?: before.currentApp,
            taskSpec.goal
        ).firstOrNull() ?: return base

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

internal object AppVerificationRules {
    fun detectSensitiveCheckpoint(
        action: Any,
        currentApp: String?,
        taskGoal: String?,
        visibleText: String
    ): VerificationResult? {
        if (action !is TapAction && action !is TypeAction && action !is BackAction) {
            return null
        }
        val evidence = listOfNotNull(currentApp, taskGoal, visibleText)
            .joinToString("\n")
            .lowercase()
        val matched = SENSITIVE_CHECKPOINT_TOKENS.firstOrNull { token ->
            evidence.contains(token.lowercase())
        } ?: return null
        return VerificationResult(
            passed = false,
            confidence = 0.96f,
            reason = "检测到敏感确认页面，需要用户确认或接管: $matched",
            observedChange = visibleText.take(120)
        )
    }

    private val SENSITIVE_CHECKPOINT_TOKENS = listOf(
        "支付密码",
        "确认付款",
        "确认支付",
        "立即支付",
        "提交订单",
        "确认订单",
        "验证码",
        "短信验证码",
        "人脸验证",
        "人脸识别",
        "实名认证",
        "绑定银行卡",
        "解绑银行卡",
        "输入密码",
        "修改密码",
        "授权登录",
        "确认登录",
        "注销账号",
        "删除账号"
    )
}
