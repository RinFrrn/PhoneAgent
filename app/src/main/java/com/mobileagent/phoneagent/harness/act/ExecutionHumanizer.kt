package com.mobileagent.phoneagent.harness.act

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import kotlinx.coroutines.delay
import java.util.Random
import kotlin.math.roundToInt

enum class ExecutionHumanizationLevel {
    LOW,
    MEDIUM,
    HIGH
}

data class ExecutionHumanizationProfile(
    val enabled: Boolean = false,
    val level: ExecutionHumanizationLevel = ExecutionHumanizationLevel.LOW,
    val timeRandomEnabled: Boolean = true,
    val positionRandomEnabled: Boolean = false,
    val positionOffsetPercentage: Float = 0.02f
) {
    fun delayRangeMs(): LongRange {
        return when (level) {
            ExecutionHumanizationLevel.LOW -> 200L..500L
            ExecutionHumanizationLevel.MEDIUM -> 300L..1000L
            ExecutionHumanizationLevel.HIGH -> 500L..2000L
        }
    }
}

data class HumanizedAction(
    val actionJson: String,
    val trace: ExecutionHumanizationTrace? = null
)

data class ExecutionHumanizationTrace(
    val enabled: Boolean,
    val level: ExecutionHumanizationLevel,
    val delayMs: Long = 0L,
    val positionRandomized: Boolean = false,
    val originalActionJson: String,
    val transformedActionJson: String,
    val reason: String
)

class ExecutionHumanizer(
    private val profile: ExecutionHumanizationProfile = ExecutionHumanizationProfile(),
    private val random: Random = Random()
) {
    suspend fun humanize(actionJson: String): HumanizedAction {
        if (!profile.enabled) {
            return HumanizedAction(actionJson)
        }

        val actionType = findActionType(actionJson) ?: return traceOnly(
            actionJson = actionJson,
            reason = "未识别动作类型，保持原动作。"
        )
        if (actionType !in HUMANIZED_ACTIONS) {
            return traceOnly(
                actionJson = actionJson,
                reason = "$actionType 动作无需人性化处理。"
            )
        }

        val delayMs = if (profile.timeRandomEnabled) nextDelayMs(profile.delayRangeMs()) else 0L
        if (delayMs > 0L) {
            delay(delayMs)
        }

        val transformed = if (profile.positionRandomEnabled) {
            randomizeCoordinates(actionType, actionJson)
        } else {
            actionJson
        }
        val positionRandomized = transformed != actionJson
        return HumanizedAction(
            actionJson = transformed,
            trace = ExecutionHumanizationTrace(
                enabled = true,
                level = profile.level,
                delayMs = delayMs,
                positionRandomized = positionRandomized,
                originalActionJson = actionJson,
                transformedActionJson = transformed,
                reason = buildReason(actionType, delayMs, positionRandomized)
            )
        )
    }

    private fun traceOnly(actionJson: String, reason: String): HumanizedAction {
        return HumanizedAction(
            actionJson = actionJson,
            trace = ExecutionHumanizationTrace(
                enabled = true,
                level = profile.level,
                originalActionJson = actionJson,
                transformedActionJson = actionJson,
                reason = reason
            )
        )
    }

    private fun nextDelayMs(range: LongRange): Long {
        val span = (range.last - range.first).coerceAtLeast(0L)
        return range.first + (random.nextDouble() * (span + 1)).toLong()
    }

    private fun randomizeCoordinates(actionType: String, actionJson: String): String {
        return when (actionType) {
            "Tap", "Click", "Long Press", "Double Tap" -> {
                replacePoint(actionJson, "element")
            }
            "Swipe" -> {
                replacePoint(replacePoint(actionJson, "start"), "end")
            }
            else -> actionJson
        }
    }

    private fun replacePoint(actionJson: String, fieldName: String): String {
        val regex = Regex(""""$fieldName"\s*:\s*\[\s*(\d+)\s*,\s*(\d+)\s*]""")
        return regex.replace(actionJson) { match ->
            val x = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            val y = match.groupValues[2].toIntOrNull() ?: return@replace match.value
            val (randomX, randomY) = randomizePoint(x, y)
            """"$fieldName":[$randomX,$randomY]"""
        }
    }

    private fun randomizePoint(x: Int, y: Int): Pair<Int, Int> {
        val maxOffset = (1000f * profile.positionOffsetPercentage)
            .roundToInt()
            .coerceAtLeast(1)
        val dx = random.nextInt(maxOffset * 2 + 1) - maxOffset
        val dy = random.nextInt(maxOffset * 2 + 1) - maxOffset
        return (x + dx).coerceIn(0, 1000) to (y + dy).coerceIn(0, 1000)
    }

    private fun buildReason(actionType: String, delayMs: Long, positionRandomized: Boolean): String {
        val parts = mutableListOf<String>()
        if (delayMs > 0L) {
            parts.add("延迟 ${delayMs}ms")
        }
        if (positionRandomized) {
            parts.add("坐标微偏移")
        }
        return if (parts.isEmpty()) {
            "$actionType 动作已检查，未改写。"
        } else {
            "$actionType 动作应用人性化执行: ${parts.joinToString("，")}。"
        }
    }

    private fun findActionType(actionJson: String): String? {
        return """"action"\s*:\s*"([^"]+)"""".toRegex()
            .find(actionJson)
            ?.groupValues
            ?.getOrNull(1)
    }

    companion object {
        private val HUMANIZED_ACTIONS = setOf(
            "Tap",
            "Click",
            "Long Press",
            "Double Tap",
            "Swipe",
            "Type",
            "Type_Name"
        )

        fun fromSettings(context: Context): ExecutionHumanizer {
            val prefs = context.getSharedPreferences(ExecutionHumanizationSettings.PREFS_NAME, MODE_PRIVATE)
            return ExecutionHumanizer(ExecutionHumanizationSettings.readProfile(prefs))
        }
    }
}

object ExecutionHumanizationSettings {
    const val PREFS_NAME = "phone_agent_settings"
    private const val KEY_ENABLED = "execution_humanization_enabled"
    private const val KEY_LEVEL = "execution_humanization_level"
    private const val KEY_TIME_RANDOM = "execution_humanization_time_random"
    private const val KEY_POSITION_RANDOM = "execution_humanization_position_random"
    private const val KEY_POSITION_OFFSET = "execution_humanization_position_offset"

    fun readProfile(prefs: SharedPreferences): ExecutionHumanizationProfile {
        val level = runCatching {
            ExecutionHumanizationLevel.valueOf(
                prefs.getString(KEY_LEVEL, ExecutionHumanizationLevel.LOW.name)
                    ?: ExecutionHumanizationLevel.LOW.name
            )
        }.getOrDefault(ExecutionHumanizationLevel.LOW)

        return ExecutionHumanizationProfile(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            level = level,
            timeRandomEnabled = prefs.getBoolean(KEY_TIME_RANDOM, true),
            positionRandomEnabled = prefs.getBoolean(KEY_POSITION_RANDOM, false),
            positionOffsetPercentage = prefs.getFloat(KEY_POSITION_OFFSET, 0.02f).coerceIn(0f, 0.1f)
        )
    }

    fun writeProfile(prefs: SharedPreferences, profile: ExecutionHumanizationProfile) {
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, profile.enabled)
            putString(KEY_LEVEL, profile.level.name)
            putBoolean(KEY_TIME_RANDOM, profile.timeRandomEnabled)
            putBoolean(KEY_POSITION_RANDOM, profile.positionRandomEnabled)
            putFloat(KEY_POSITION_OFFSET, profile.positionOffsetPercentage.coerceIn(0f, 0.1f))
            apply()
        }
    }

    fun offsetPercentToFraction(text: String): Float {
        return text.trim()
            .removeSuffix("%")
            .trim()
            .toFloatOrNull()
            ?.div(100f)
            ?.coerceIn(0f, 0.1f)
            ?: 0.02f
    }

    fun offsetFractionToPercentText(value: Float): String {
        val percent = (value.coerceIn(0f, 0.1f) * 100f)
        return if (percent % 1f == 0f) {
            percent.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", percent)
        }
    }
}
