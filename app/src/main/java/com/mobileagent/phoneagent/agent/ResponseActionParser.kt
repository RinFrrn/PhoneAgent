package com.mobileagent.phoneagent.agent

import android.util.Log
import org.json.JSONObject

class ResponseActionParser {
    private val tag = "ResponseActionParser"

    fun parseActionFromResponse(response: String): String {
        Log.d(tag, "解析操作响应: ${response.take(200)}...")

        var braceCount = 0
        var startIndex = -1
        var endIndex = -1

        for (i in response.indices) {
            when (response[i]) {
                '{' -> {
                    if (startIndex == -1) startIndex = i
                    braceCount++
                }
                '}' -> {
                    braceCount--
                    if (braceCount == 0 && startIndex != -1) {
                        endIndex = i
                        break
                    }
                }
            }
        }

        if (startIndex != -1 && endIndex != -1) {
            val jsonStr = response.substring(startIndex, endIndex + 1)
            try {
                JSONObject(jsonStr)
                Log.d(tag, "提取到完整 JSON: $jsonStr")
                return jsonStr
            } catch (e: Exception) {
                Log.w(tag, "提取的 JSON 无效，尝试其他方法", e)
            }
        }

        return parseActionFromCode(response)
    }

    private fun parseActionFromCode(code: String): String {
        val json = JSONObject()

        when {
            code.contains("finish(") -> {
                json.put("_metadata", "finish")
                val messageMatch = """message=["']([^"']+)["']""".toRegex().find(code)
                if (messageMatch != null) {
                    json.put("message", messageMatch.groupValues[1])
                }
            }
            code.contains("do(") -> {
                json.put("_metadata", "do")
                val actionMatch = """action=["']([^"']+)["']""".toRegex().find(code)
                if (actionMatch != null) {
                    val action = actionMatch.groupValues[1]
                    json.put("action", action)

                    when (action) {
                        "Tap", "Click" -> {
                            var elementMatch = """element=\[(\d+),\s*(\d+)\]""".toRegex().find(code)
                            if (elementMatch == null) {
                                elementMatch = """element=["'](\d+),\s*(\d+)["']""".toRegex().find(code)
                            }
                            if (elementMatch != null) {
                                json.put("element", org.json.JSONArray().apply {
                                    put(elementMatch.groupValues[1].toInt())
                                    put(elementMatch.groupValues[2].toInt())
                                })
                            }
                            json.put("action", "Tap")
                        }
                        "Type", "Type_Name" -> {
                            val textMatch = """text=["']([^"']+)["']""".toRegex().find(code)
                            if (textMatch != null) {
                                json.put("text", textMatch.groupValues[1])
                            }
                        }
                        "Swipe" -> {
                            val startMatch = """start=\[(\d+),(\d+)\]""".toRegex().find(code)
                            val endMatch = """end=\[(\d+),(\d+)\]""".toRegex().find(code)
                            if (startMatch != null && endMatch != null) {
                                json.put("start", org.json.JSONArray().apply {
                                    put(startMatch.groupValues[1].toInt())
                                    put(startMatch.groupValues[2].toInt())
                                })
                                json.put("end", org.json.JSONArray().apply {
                                    put(endMatch.groupValues[1].toInt())
                                    put(endMatch.groupValues[2].toInt())
                                })
                            }
                        }
                        "Launch" -> {
                            val appMatch = """app=["']([^"']+)["']""".toRegex().find(code)
                            if (appMatch != null) {
                                json.put("app", appMatch.groupValues[1])
                            }
                        }
                    }
                }
            }
            else -> {
                json.put("_metadata", "finish")
                json.put("message", code)
            }
        }

        return json.toString()
    }
}
