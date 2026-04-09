package com.mobileagent.phoneagent.skill

import android.content.Context
import com.mobileagent.phoneagent.action.ActionResult
import org.json.JSONArray
import org.json.JSONObject

class SkillActionInterceptor {
    fun fallbackActions(
        context: Context,
        currentApp: String?,
        task: String?,
        actionJson: String,
        actionResult: ActionResult
    ): List<String> {
        if (actionResult.success) {
            return emptyList()
        }

        val json = try {
            JSONObject(actionJson)
        } catch (_: Exception) {
            return emptyList()
        }

        if (json.optString("_metadata") != "do") {
            return emptyList()
        }

        val matchedSkill = SkillRegistry.matchingSkills(context, currentApp, task).firstOrNull() ?: return emptyList()

        return when (matchedSkill.fallbackProfile) {
            "wechat" -> wechatFallbacks(json)
            "content_app" -> contentAppFallbacks(json)
            "commerce" -> commerceFallbacks(json)
            else -> emptyList()
        }
    }

    private fun wechatFallbacks(json: JSONObject): List<String> {
        return when (json.optString("action")) {
            "Back" -> listOf(buildTapAction(70, 90), buildTapAction(110, 90))
            "Tap", "Click" -> {
                val (x, y) = parseElement(json) ?: return emptyList()
                when {
                    y < 180 && x < 260 -> listOf(buildTapAction((x + 40).coerceAtMost(220), y + 20))
                    y in 180..320 -> listOf(buildTapAction(x, (y + 40).coerceAtMost(360)))
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }
    }

    private fun contentAppFallbacks(json: JSONObject): List<String> {
        return when (json.optString("action")) {
            "Back" -> listOf(buildTapAction(80, 110), buildTapAction(120, 110))
            "Tap", "Click" -> {
                val (x, y) = parseElement(json) ?: return emptyList()
                if (y < 220) {
                    listOf(buildTapAction((x + 40).coerceAtMost(220), (y + 20).coerceAtMost(220)))
                } else {
                    emptyList()
                }
            }
            "Swipe" -> {
                val start = json.optJSONArray("start")
                val end = json.optJSONArray("end")
                if (start == null || end == null) return emptyList()
                val startX = start.optInt(0)
                val endX = end.optInt(0)
                val startY = start.optInt(1)
                val endY = end.optInt(1)
                listOf(buildSwipeAction(startX, (startY + 80).coerceAtMost(900), endX, (endY - 80).coerceAtLeast(100)))
            }
            else -> emptyList()
        }
    }

    private fun commerceFallbacks(json: JSONObject): List<String> {
        return when (json.optString("action")) {
            "Tap", "Click" -> {
                val (x, y) = parseElement(json) ?: return emptyList()
                if (y > 760) {
                    listOf(
                        buildTapAction(x, (y - 70).coerceAtLeast(640)),
                        buildTapAction(x, (y - 120).coerceAtLeast(600))
                    )
                } else {
                    listOf(
                        buildTapAction((x + 35).coerceAtMost(950), y),
                        buildTapAction((x - 35).coerceAtLeast(50), y)
                    )
                }
            }
            "Swipe" -> {
                val start = json.optJSONArray("start")
                val end = json.optJSONArray("end")
                if (start == null || end == null) return emptyList()
                val startX = start.optInt(0)
                val endX = end.optInt(0)
                val startY = start.optInt(1)
                val endY = end.optInt(1)
                listOf(
                    buildSwipeAction(startX, (startY + 120).coerceAtMost(950), endX, (endY - 120).coerceAtLeast(50)),
                    buildSwipeAction(500, 820, 500, 260)
                )
            }
            "Back" -> listOf(buildTapAction(80, 110))
            else -> emptyList()
        }
    }

    private fun parseElement(json: JSONObject): Pair<Int, Int>? {
        val element = json.optJSONArray("element") ?: return null
        return Pair(element.optInt(0), element.optInt(1))
    }

    private fun buildTapAction(x: Int, y: Int): String {
        return JSONObject().apply {
            put("_metadata", "do")
            put("action", "Tap")
            put("element", JSONArray().apply {
                put(x.coerceIn(0, 999))
                put(y.coerceIn(0, 999))
            })
        }.toString()
    }

    private fun buildSwipeAction(startX: Int, startY: Int, endX: Int, endY: Int): String {
        return JSONObject().apply {
            put("_metadata", "do")
            put("action", "Swipe")
            put("start", JSONArray().apply {
                put(startX.coerceIn(0, 999))
                put(startY.coerceIn(0, 999))
            })
            put("end", JSONArray().apply {
                put(endX.coerceIn(0, 999))
                put(endY.coerceIn(0, 999))
            })
        }.toString()
    }
}
