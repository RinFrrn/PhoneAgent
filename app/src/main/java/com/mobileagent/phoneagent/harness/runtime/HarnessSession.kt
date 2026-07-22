package com.mobileagent.phoneagent.harness.runtime

import com.mobileagent.phoneagent.action.Action
import com.mobileagent.phoneagent.action.ActionParser
import com.mobileagent.phoneagent.action.BackAction
import com.mobileagent.phoneagent.action.DragAction
import com.mobileagent.phoneagent.action.HomeAction
import com.mobileagent.phoneagent.action.KeyEventAction
import com.mobileagent.phoneagent.action.LaunchAction
import com.mobileagent.phoneagent.action.RecentAppsAction
import com.mobileagent.phoneagent.action.SwipeAction
import com.mobileagent.phoneagent.action.TapAction
import com.mobileagent.phoneagent.action.WaitAction
import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.recover.FailureType
import com.mobileagent.phoneagent.harness.spec.TaskSpec
import com.mobileagent.phoneagent.harness.verify.VerificationResult

data class HarnessSession(
    val taskSpec: TaskSpec,
    var stepCount: Int = 0
) {
    private val actionParser = ActionParser()
    private val recoveryAttempts = mutableMapOf<FailureType, Int>()

    var lastPageFingerprint: String? = null
        private set
    var lastActionFingerprint: String? = null
        private set
    var consecutiveIneffectiveActions: Int = 0
        private set

    fun nextStepIndex(): Int {
        stepCount += 1
        return stepCount
    }

    fun recordRecoveryFailure(failureType: FailureType): Int {
        val attempt = recoveryAttempts.getOrDefault(failureType, 0) + 1
        recoveryAttempts[failureType] = attempt
        return attempt
    }

    fun resetRecoveryFailures(vararg failureTypes: FailureType) {
        failureTypes.forEach(recoveryAttempts::remove)
    }

    fun resetExecutionRecoveryFailures() {
        resetRecoveryFailures(
            FailureType.ACTION_EXECUTION_FAILED,
            FailureType.ACTION_NOT_EFFECTIVE,
            FailureType.VERIFICATION_FAILED,
            FailureType.APP_NOT_FOUND,
            FailureType.APP_LAUNCH_BLOCKED,
            FailureType.APP_LAUNCH_CONFIRMATION_REQUIRED,
            FailureType.APP_LAUNCH_TARGET_NOT_REACHED,
            FailureType.SENSITIVE_CONFIRMATION_REQUIRED,
            FailureType.RECORDED_TARGET_MISSING,
            FailureType.RECORDED_STATE_TIMEOUT,
            FailureType.RECORDED_OBSTRUCTION_DETECTED,
            FailureType.USER_TAKEOVER_REQUIRED,
            FailureType.UNKNOWN
        )
    }

    fun recordStepOutcome(
        actionJson: String,
        before: Observation,
        after: Observation?,
        verification: VerificationResult
    ): StagnationResult {
        val action = runCatching { actionParser.parse(actionJson) }.getOrNull()
        val actionFingerprint = action?.toActionFingerprint() ?: fallbackActionFingerprint(actionJson)
        val beforeFingerprint = before.toPageFingerprint()
        val afterFingerprint = after?.toPageFingerprint()
        val pageUnchanged = afterFingerprint != null && afterFingerprint == beforeFingerprint
        val repeatAction = lastActionFingerprint == actionFingerprint
        val trackedAction = action?.countsForStagnation() ?: fallbackCountsForStagnation(actionJson)
        val waitAction = action is WaitAction || fallbackActionType(actionJson) == "Wait"
        val ineffective = trackedAction && !verification.passed && pageUnchanged

        if (ineffective) {
            consecutiveIneffectiveActions += 1
        } else if (verification.passed || !waitAction) {
            consecutiveIneffectiveActions = 0
        }

        lastPageFingerprint = afterFingerprint ?: beforeFingerprint
        lastActionFingerprint = actionFingerprint

        return StagnationResult(
            ineffective = ineffective,
            pageUnchanged = pageUnchanged,
            repeatAction = repeatAction,
            actionFingerprint = actionFingerprint,
            pageFingerprint = afterFingerprint ?: beforeFingerprint,
            consecutiveIneffectiveActions = consecutiveIneffectiveActions
        )
    }

    private fun Action.countsForStagnation(): Boolean {
        return this is TapAction ||
            this is SwipeAction ||
            this is DragAction ||
            this is BackAction ||
            this is HomeAction ||
            this is RecentAppsAction ||
            this is KeyEventAction
    }

    private fun fallbackCountsForStagnation(actionJson: String): Boolean {
        val type = fallbackActionType(actionJson) ?: return false
        return when (type.lowercase()) {
            "tap", "click", "swipe", "drag", "back", "home" -> true
            "press_key" -> fallbackActionKey(actionJson)?.lowercase() in setOf("back", "home", "recent", "recents", "overview")
            "key_event", "keyevent" -> fallbackActionKey(actionJson)?.normalizedKey() in supportedSystemKeyEvents
            else -> false
        }
    }

    private fun Action.toActionFingerprint(): String {
        return when (this) {
            is TapAction -> "Tap:$x,$y"
            is SwipeAction -> "Swipe:$startX,$startY->$endX,$endY"
            is DragAction -> "Drag:$startX,$startY->$endX,$endY:${durationMs}ms"
            is BackAction -> "Back"
            is HomeAction -> "Home"
            is RecentAppsAction -> "RecentApps"
            is KeyEventAction -> "KeyEvent:${event.name}:${key}"
            is WaitAction -> "Wait"
            is LaunchAction -> "Launch:$appName"
            else -> this::class.simpleName ?: "UnknownAction"
        }
    }

    private fun fallbackActionFingerprint(actionJson: String): String {
        val type = fallbackActionType(actionJson) ?: return actionJson.take(120)
        if (type.equals("press_key", ignoreCase = true)) {
            return "PressKey:${fallbackActionKey(actionJson).orEmpty()}"
        }
        if (type.equals("key_event", ignoreCase = true) || type.equals("keyevent", ignoreCase = true)) {
            return "KeyEvent:${fallbackActionKey(actionJson).orEmpty()}"
        }
        return type
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

    private val supportedSystemKeyEvents = setOf(
        "back",
        "home",
        "recent",
        "recents",
        "overview",
        "notifications",
        "notification_shade",
        "notification",
        "quick_settings",
        "quicksettings",
        "settings_panel",
        "power_dialog",
        "power_menu",
        "global_actions",
        "power",
        "keycode_power",
        "lock_screen",
        "lock",
        "keycode_sleep"
    )

    private fun String.normalizedKey(): String {
        return trim().replace("-", "_").lowercase()
    }

    private fun Observation.toPageFingerprint(): String {
        return listOf(
            currentPackage.orEmpty(),
            currentApp.orEmpty(),
            contentItems
                .filter { it.type == "text" }
                .mapNotNull { it.text }
                .joinToString("\n")
                .replace("\\s+".toRegex(), " ")
                .trim()
        ).joinToString("|")
    }
}

data class StagnationResult(
    val ineffective: Boolean,
    val pageUnchanged: Boolean,
    val repeatAction: Boolean,
    val actionFingerprint: String,
    val pageFingerprint: String,
    val consecutiveIneffectiveActions: Int
)
