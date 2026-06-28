package com.mobileagent.phoneagent.harness.runtime

import com.mobileagent.phoneagent.action.Action
import com.mobileagent.phoneagent.action.ActionParser
import com.mobileagent.phoneagent.action.BackAction
import com.mobileagent.phoneagent.action.HomeAction
import com.mobileagent.phoneagent.action.LaunchAction
import com.mobileagent.phoneagent.action.SwipeAction
import com.mobileagent.phoneagent.action.TapAction
import com.mobileagent.phoneagent.action.WaitAction
import com.mobileagent.phoneagent.harness.observe.Observation
import com.mobileagent.phoneagent.harness.spec.TaskSpec
import com.mobileagent.phoneagent.harness.verify.VerificationResult

data class HarnessSession(
    val taskSpec: TaskSpec,
    var stepCount: Int = 0
) {
    private val actionParser = ActionParser()

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
        return this is TapAction || this is SwipeAction || this is BackAction || this is HomeAction
    }

    private fun fallbackCountsForStagnation(actionJson: String): Boolean {
        val type = fallbackActionType(actionJson) ?: return false
        return type in setOf("Tap", "Click", "Swipe", "Back", "Home")
    }

    private fun Action.toActionFingerprint(): String {
        return when (this) {
            is TapAction -> "Tap:$x,$y"
            is SwipeAction -> "Swipe:$startX,$startY->$endX,$endY"
            is BackAction -> "Back"
            is HomeAction -> "Home"
            is WaitAction -> "Wait"
            is LaunchAction -> "Launch:$appName"
            else -> this::class.simpleName ?: "UnknownAction"
        }
    }

    private fun fallbackActionFingerprint(actionJson: String): String {
        return fallbackActionType(actionJson) ?: actionJson.take(120)
    }

    private fun fallbackActionType(actionJson: String): String? {
        return """"action"\s*:\s*"([^"]+)"""".toRegex()
            .find(actionJson)
            ?.groupValues
            ?.getOrNull(1)
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
