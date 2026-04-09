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
            "finish" -> FinishAction(json.optString("message", ""))
            "do" -> parseDoAction(json)
            else -> UnknownAction(metadata)
        }
    }

    private fun parseDoAction(json: JSONObject): Action {
        return when (val actionType = json.optString("action", "")) {
            "Tap", "Click" -> {
                val (x, y) = parseElementCoordinates(json)
                Log.d(tag, "解析点击坐标（相对）: ($x, $y)")
                TapAction(
                    x = x,
                    y = y,
                    message = json.optString("message")
                )
            }
            "Type", "Type_Name" -> TypeAction(json.optString("text", ""))
            "Swipe" -> {
                val start = json.optJSONArray("start")
                val end = json.optJSONArray("end")
                SwipeAction(
                    startX = start?.optInt(0) ?: 0,
                    startY = start?.optInt(1) ?: 0,
                    endX = end?.optInt(0) ?: 0,
                    endY = end?.optInt(1) ?: 0
                )
            }
            "Long Press" -> {
                val element = json.optJSONArray("element")
                LongPressAction(
                    x = element?.optInt(0) ?: 0,
                    y = element?.optInt(1) ?: 0
                )
            }
            "Double Tap" -> {
                val element = json.optJSONArray("element")
                DoubleTapAction(
                    x = element?.optInt(0) ?: 0,
                    y = element?.optInt(1) ?: 0
                )
            }
            "Launch" -> LaunchAction(json.optString("app", ""))
            "Back" -> BackAction
            "Home" -> HomeAction
            "Wait" -> WaitAction(parseDuration(json.optString("duration", "1 seconds")))
            "Take_over" -> TakeOverAction(json.optString("message", ""))
            "Note" -> NoteAction(json.optString("message", "True"))
            "Call_API" -> CallAPIAction(json.optString("instruction", ""))
            "Interact" -> InteractAction
            else -> UnknownAction(actionType)
        }
    }

    private fun parseElementCoordinates(json: JSONObject): Pair<Int, Int> {
        val elementArray = json.optJSONArray("element")
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

    private fun parseDuration(duration: String): Long {
        val regex = """(\d+)\s*(seconds?|秒)""".toRegex(RegexOption.IGNORE_CASE)
        val match = regex.find(duration)
        return if (match != null) {
            match.groupValues[1].toLongOrNull()?.times(1000) ?: 1000
        } else {
            1000
        }
    }
}
