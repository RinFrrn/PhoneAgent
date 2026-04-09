package com.mobileagent.phoneagent.agent

import android.util.Log
import com.mobileagent.phoneagent.model.ContentItem
import com.mobileagent.phoneagent.model.Message
import com.mobileagent.phoneagent.model.ModelClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SessionMemory(
    private val modelClient: ModelClient,
    private val mode: Mode,
    private val contextSizeThreshold: Int = 4_000_000,
    private val minMessagesToKeep: Int = 4
) {
    private val tag = "SessionMemory"
    private val messages = mutableListOf<Message>()

    var compressedHistory: String? = null
        private set

    fun clear() {
        messages.clear()
        compressedHistory = null
    }

    fun add(message: Message) {
        messages.add(message)
    }

    fun addSystemMessage(systemPrompt: String) {
        messages.add(Message("system", systemPrompt))
    }

    fun addObservation(contentItems: List<ContentItem>) {
        messages.add(Message("user", contentItems))
    }

    fun addTaskUpdate(oldTask: String?, newTask: String) {
        messages.add(
            Message(
                "user",
                "** 📝 任务已更新 **\n\n" +
                    "原任务目标: $oldTask\n" +
                    "新任务目标: $newTask\n\n" +
                    "请根据新的任务目标继续执行。如果新任务与当前状态不符，请先返回或重新开始。"
            )
        )
    }

    fun addInterventionMessage(message: String) {
        messages.add(
            Message(
                "user",
                "** ⚠️ 用户介入提示 **\n" +
                    "$message\n\n" +
                    "用户已完成介入操作，请继续执行任务。分析当前屏幕状态，继续下一步操作。"
            )
        )
    }

    fun addAssistantResponse(rawContent: String) {
        messages.add(Message("assistant", rawContent))
    }

    fun addFailureFeedback(message: String) {
        messages.add(
            Message(
                "user",
                "⚠️ 上次操作失败: $message\n" +
                    "请分析失败原因，并尝试完全不同的方法。不要重复相同的操作。"
            )
        )
    }

    fun addReplanHint(lastFailedAction: String?) {
        messages.add(
            Message(
                "user",
                "上次操作失败，请尝试不同的方法。失败的操作: $lastFailedAction"
            )
        )
    }

    suspend fun messagesForRequest(): List<Message> {
        val currentSize = calculateContextSize(messages)
        if (currentSize <= contextSizeThreshold) {
            return messages.toList()
        }

        Log.w(tag, "上下文超过阈值 (${currentSize / 1024}KB > ${contextSizeThreshold / 1024}KB)，开始智能压缩...")

        val systemMessage = messages.firstOrNull { it.role == "system" }
        val otherMessages = messages.filter { it.role != "system" }
        val keepCount = (minMessagesToKeep * 2).coerceAtMost(otherMessages.size)
        val recentMessages = otherMessages.takeLast(keepCount)
        val oldMessages = otherMessages.dropLast(keepCount)

        if (oldMessages.isEmpty()) {
            Log.d(tag, "没有需要压缩的历史消息，但上下文仍然很大")
            return messages.toList()
        }

        Log.d(tag, "准备压缩 ${oldMessages.size} 条历史消息，保留 ${recentMessages.size} 条最新消息")
        val compressedSummary = compressHistoryMessages(oldMessages)

        val compressedMessages = mutableListOf<Message>()
        systemMessage?.let { compressedMessages.add(it) }
        if (compressedSummary.isNotEmpty()) {
            compressedMessages.add(
                Message(
                    "user",
                    "** 📋 历史操作摘要 **\n" +
                        compressedSummary +
                        "\n\n---\n" +
                        "以上是之前的操作历史摘要。请基于此摘要和当前屏幕状态继续执行任务。"
                )
            )
        }
        compressedMessages.addAll(recentMessages)

        val newSize = calculateContextSize(compressedMessages)
        Log.d(tag, "上下文压缩完成: ${currentSize / 1024}KB -> ${newSize / 1024}KB")
        if (newSize > contextSizeThreshold) {
            Log.w(tag, "压缩后仍然超过阈值，可能是当前截图太大")
        }

        return compressedMessages
    }

    fun currentMessageCount(): Int = messages.size

    fun calculateCurrentContextSize(): Int = calculateContextSize(messages)

    fun removeImageFromLastUserMessage() {
        if (messages.isEmpty() || mode == Mode.ACCESSIBILITY) {
            return
        }

        for (i in messages.size - 1 downTo 0) {
            val message = messages[i]
            if (message.role != "user") {
                continue
            }

            when (val content = message.content) {
                is List<*> -> {
                    val items = content.filterIsInstance<ContentItem>()
                    val hasImage = items.any { it.type == "image_url" }
                    if (hasImage) {
                        val textItems = items.filter { it.type == "text" }
                        messages[i] = if (textItems.isNotEmpty()) {
                            Message(message.role, textItems)
                        } else {
                            Message(
                                message.role,
                                "屏幕内容已采集（图片已移除以节省上下文空间）"
                            )
                        }
                        Log.d(tag, "已移除最后一条用户消息中的图片")
                    }
                    return
                }
            }
        }
    }

    private fun calculateContextSize(messages: List<Message>): Int {
        return messages.sumOf { message ->
            when (val content = message.content) {
                is String -> content.length
                is List<*> -> {
                    content.filterIsInstance<ContentItem>().sumOf { item ->
                        when (item.type) {
                            "text" -> item.text?.length ?: 0
                            "image_url" -> item.imageUrl?.url?.length ?: 0
                            else -> 0
                        }
                    }
                }
                else -> 0
            }
        }
    }

    private suspend fun compressHistoryMessages(oldMessages: List<Message>): String = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, "开始压缩 ${oldMessages.size} 条历史消息...")
            val historyText = oldMessages.mapNotNull { message ->
                val content = when (val msgContent = message.content) {
                    is String -> msgContent
                    is List<*> -> {
                        val textParts = msgContent.filterIsInstance<ContentItem>()
                            .filter { it.type == "text" }
                            .mapNotNull { it.text }
                        if (textParts.isNotEmpty()) textParts.joinToString("\n") else null
                    }
                    else -> null
                }

                if (content.isNullOrBlank()) {
                    null
                } else {
                    when (message.role) {
                        "user" -> "用户: $content"
                        "assistant" -> "助手: $content"
                        else -> "${message.role}: $content"
                    }
                }
            }.joinToString("\n\n")

            if (historyText.isBlank()) {
                Log.w(tag, "历史消息中没有文本内容，使用简单摘要")
                return@withContext "已执行 ${oldMessages.size / 2} 步操作，继续执行任务。"
            }

            val compressPrompt = """
                请总结以下对话历史，提取关键信息：
                1. 任务目标是什么
                2. 已执行了哪些主要操作（列出关键步骤）
                3. 遇到了什么困难，如何解决的
                4. 当前处于什么状态

                请用简洁的中文总结，保留重要信息，忽略细节和图片描述。
                总结格式：
                - 任务目标：[目标]
                - 已执行操作：[操作列表]
                - 遇到问题：[问题及解决方案]
                - 当前状态：[状态]
            """.trimIndent()

            val compressMessages = listOf(
                Message("system", "你是一个对话历史总结专家，能够提取关键信息并压缩对话内容。请用简洁的中文总结。"),
                Message("user", "$compressPrompt\n\n对话历史：\n$historyText")
            )

            val response = modelClient.request(compressMessages)
            val summary = response.rawContent.trim()
            if (summary.isBlank()) {
                throw Exception("压缩结果为空")
            }

            compressedHistory = summary
            Log.d(tag, "历史消息压缩完成，摘要长度: ${summary.length}")
            summary
        } catch (e: Exception) {
            Log.e(tag, "压缩历史消息失败", e)
            val stepCount = oldMessages.count { it.role == "user" }
            "已执行约 $stepCount 步操作，继续执行任务。如果遇到问题，请尝试不同的方法。"
        }
    }
}
