package com.mobileagent.phoneagent.action

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class ActionParser {
    private val tag = "ActionParser"

    fun parse(actionJson: String): Action {
        val json = JSONObject(actionJson)
        val metadata = json.optString("_metadata", "")

        return when (metadata) {
            "finish" -> FinishAction(
                message = json.optString("message", ""),
                success = json.optBoolean("success", true)
            )
            "do" -> parseDoAction(json)
            "" -> if (json.has("action")) parseDoAction(json) else UnknownAction(metadata)
            else -> UnknownAction(metadata)
        }
    }

    private fun parseDoAction(json: JSONObject): Action {
        val actionType = json.optString("action", "")
        val normalizedAction = actionType.lowercase()
        return when (normalizedAction) {
            "tap", "click" -> {
                val (x, y) = parseElementCoordinates(json)
                Log.d(tag, "解析点击坐标（相对）: ($x, $y)")
                TapAction(
                    x = x,
                    y = y,
                    message = json.optString("message")
                )
            }
            "type", "type_name", "input_text" -> TypeAction(json.optString("text", ""))
            "swipe" -> {
                val start = json.optJSONArray("start")
                val end = json.optJSONArray("end")
                if (start != null && end != null) {
                    SwipeAction(
                        startX = start.optInt(0),
                        startY = start.optInt(1),
                        endX = end.optInt(0),
                        endY = end.optInt(1)
                    )
                } else {
                    StandardSwipeMapper.fromDirection(json.optString("direction", ""))
                        ?: UnknownAction(actionType)
                }
            }
            "scroll" -> parseScroll(json)
            "long press", "long_press", "longpress" -> {
                val element = json.optJSONArray("element") ?: json.optJSONArray("coordinates")
                LongPressAction(
                    x = element?.optInt(0) ?: 0,
                    y = element?.optInt(1) ?: 0
                )
            }
            "double tap", "double_tap", "doubletap" -> {
                val element = json.optJSONArray("element") ?: json.optJSONArray("coordinates")
                DoubleTapAction(
                    x = element?.optInt(0) ?: 0,
                    y = element?.optInt(1) ?: 0
                )
            }
            "drag" -> parseDrag(json)
            "launch", "launch_app" -> LaunchAction(
                json.optString("app", "").ifBlank { json.optString("app_name", "") }
            )
            "back" -> BackAction
            "home" -> HomeAction
            "press_key" -> parsePressKey(json)
            "key_event", "keyevent" -> parseKeyEvent(json)
            "wait" -> WaitAction(parseWaitDuration(json))
            "done", "finish" -> FinishAction(
                message = json.optString("message", "").ifBlank {
                    json.optString("reason", "任务完成")
                },
                success = json.optBoolean("success", true)
            )
            "take_over" -> TakeOverAction(json.optString("message", ""))
            "answer" -> AnswerAction(
                answer = json.optString("answer", "").ifBlank {
                    json.optString("message", "")
                },
                success = json.optBoolean("success", true),
                reason = json.optString("reason", "").ifBlank {
                    json.optString("purpose", "")
                }
            )
            "read_clipboard", "readclipboard" -> ReadClipboardAction(
                reason = json.optString("reason", "").ifBlank {
                    json.optString("purpose", "读取剪贴板内容")
                }
            )
            "write_clipboard", "writeclipboard" -> WriteClipboardAction(
                text = json.optString("text", ""),
                reason = json.optString("reason", "").ifBlank {
                    json.optString("purpose", "写入剪贴板")
                }
            )
            "ask_user", "askuser" -> AskUserAction(
                question = json.optString("question", "").ifBlank {
                    json.optString("message", "需要用户确认下一步")
                },
                options = parseOptions(json.optJSONArray("options")),
                reason = json.optString("reason", "").ifBlank {
                    json.optString("purpose", "")
                },
                kind = UserInteractionKind.fromWireValue(json.optString("interaction_kind", ""))
            )
            "record_important_content", "record" -> NoteAction(
                json.optString("content", "").ifBlank { json.optString("message", "True") }
            )
            "generate_or_update_todos", "todos" -> NoteAction(
                json.optString("todos", "").ifBlank { json.optString("message", "True") }
            )
            "note" -> NoteAction(json.optString("message", "True"))
            "call_api" -> CallAPIAction(json.optString("instruction", ""))
            "interact" -> InteractAction
            else -> UnknownAction(actionType)
        }
    }

    private fun parseElementCoordinates(json: JSONObject): Pair<Int, Int> {
        val elementArray = json.optJSONArray("element") ?: json.optJSONArray("coordinates")
        val elementString = json.optString("element", "")

        return when {
            elementArray != null -> parseCoordinateArray(elementArray)
            elementString.isNotEmpty() -> {
                val coords = elementString.split(",").map { it.trim().toIntOrNull() ?: 0 }
                Pair(coords.getOrElse(0) { 0 }, coords.getOrElse(1) { 0 })
            }
            else -> Pair(0, 0)
        }
    }

    private fun parseCoordinateArray(array: JSONArray): Pair<Int, Int> {
        return Pair(array.optInt(0), array.optInt(1))
    }

    private fun parseOptions(array: JSONArray?): List<String> {
        if (array == null) {
            return emptyList()
        }
        return (0 until array.length())
            .mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
    }

    private fun parseDuration(duration: String): Long {
        val regex = """(\d+)\s*(seconds?|秒)""".toRegex(RegexOption.IGNORE_CASE)
        val match = regex.find(duration)
        return if (match != null) {
            match.groupValues[1].toLongOrNull()?.times(1000) ?: 1000
        } else {
            1000
        }
    }

    private fun parseWaitDuration(json: JSONObject): Long {
        if (json.has("seconds")) {
            val seconds = json.optDouble("seconds", 1.0)
            return (seconds * 1000).toLong().coerceAtLeast(0L)
        }
        return parseDuration(json.optString("duration", "1 seconds"))
    }

    private fun parsePressKey(json: JSONObject): Action {
        return StandardKeyMapper.fromKey(json.optString("key", ""))
    }

    private fun parseKeyEvent(json: JSONObject): Action {
        return StandardKeyEventMapper.fromKey(json.optString("key", ""))
    }

    private fun parseDrag(json: JSONObject): Action {
        return StandardDragMapper.fromPoints(
            start = json.optJSONArray("start")?.toIntList(),
            end = json.optJSONArray("end")?.toIntList(),
            durationMs = json.optLong("duration", 500L)
        ) ?: UnknownAction("drag")
    }

    private fun parseScroll(json: JSONObject): Action {
        val coordinates = json.optJSONArray("coordinates")
        val x = coordinates?.optInt(0) ?: 500
        val y = coordinates?.optInt(1) ?: 540
        return StandardSwipeMapper.fromScroll(
            anchorX = x,
            anchorY = y,
            value = json.optInt("value", 0)
        ) ?: UnknownAction("scroll")
    }

    private fun JSONArray.toIntList(): List<Int> {
        return (0 until length()).map { index -> optInt(index) }
    }
}

object StandardSwipeMapper {
    fun fromDirection(direction: String): SwipeAction? {
        return when (direction.lowercase()) {
            "up" -> SwipeAction(500, 820, 500, 260)
            "down" -> SwipeAction(500, 260, 500, 820)
            "left" -> SwipeAction(820, 520, 180, 520)
            "right" -> SwipeAction(180, 520, 820, 520)
            else -> null
        }
    }

    fun fromScroll(anchorX: Int, anchorY: Int, value: Int): SwipeAction? {
        if (value == 0) {
            return null
        }
        val x = anchorX.coerceIn(80, 920)
        val y = anchorY.coerceIn(180, 820)
        val distance = kotlin.math.abs(value).coerceIn(160, 560)
        return if (value > 0) {
            SwipeAction(
                startX = x,
                startY = (y + distance / 2).coerceAtMost(920),
                endX = x,
                endY = (y - distance / 2).coerceAtLeast(80)
            )
        } else {
            SwipeAction(
                startX = x,
                startY = (y - distance / 2).coerceAtLeast(80),
                endX = x,
                endY = (y + distance / 2).coerceAtMost(920)
            )
        }
    }
}

object StandardKeyMapper {
    fun fromKey(key: String): Action {
        return when (key.lowercase()) {
            "back" -> BackAction
            "home" -> HomeAction
            "recent", "recents", "overview" -> RecentAppsAction
            else -> UnknownAction("press_key")
        }
    }
}

object StandardDragMapper {
    fun fromPoints(start: List<Int>?, end: List<Int>?, durationMs: Long = 500L): DragAction? {
        if (start == null || end == null || start.size < 2 || end.size < 2) {
            return null
        }
        return DragAction(
            startX = start[0],
            startY = start[1],
            endX = end[0],
            endY = end[1],
            durationMs = durationMs.coerceIn(100L, 5_000L)
        )
    }
}

enum class StandardSystemKeyEvent {
    NOTIFICATIONS,
    QUICK_SETTINGS,
    POWER_DIALOG,
    LOCK_SCREEN
}

object StandardKeyEventMapper {
    fun fromKey(key: String): Action {
        return when (normalize(key)) {
            "back" -> BackAction
            "home" -> HomeAction
            "recent", "recents", "overview" -> RecentAppsAction
            "notifications", "notification_shade", "notification", "keycode_notification" ->
                KeyEventAction(key, StandardSystemKeyEvent.NOTIFICATIONS)
            "quick_settings", "quicksettings", "settings_panel", "keycode_quick_settings" ->
                KeyEventAction(key, StandardSystemKeyEvent.QUICK_SETTINGS)
            "power_dialog", "power_menu", "global_actions", "power", "keycode_power" ->
                KeyEventAction(key, StandardSystemKeyEvent.POWER_DIALOG)
            "lock_screen", "lock", "keycode_sleep" ->
                KeyEventAction(key, StandardSystemKeyEvent.LOCK_SCREEN)
            else -> UnknownAction("key_event")
        }
    }

    private fun normalize(key: String): String {
        return key.trim().replace("-", "_").lowercase()
    }
}
