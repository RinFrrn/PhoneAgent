package com.mobileagent.phoneagent.harness.trace

data class RecentTaskPerformanceSummary(
    val taskCount: Int,
    val finishedCount: Int,
    val successCount: Int,
    val failedCount: Int,
    val stoppedCount: Int,
    val runningCount: Int,
    val totalSteps: Int,
    val modelCallSummary: ModelCallSummary
) {
    fun isEmpty(): Boolean = taskCount == 0

    fun toDisplayText(): String {
        if (isEmpty()) {
            return "最近任务：暂无可统计记录"
        }

        val resultText = if (finishedCount > 0) {
            "成功 $successCount/$finishedCount"
        } else {
            "暂无完成"
        }
        val runningText = if (runningCount > 0) " · 运行中 $runningCount" else ""
        val modelText = if (modelCallSummary.isEmpty()) {
            "模型未记录"
        } else {
            val tokenText = modelCallSummary.totalTokens?.let { " · tokens $it" } ?: ""
            "模型 ${modelCallSummary.callCount} 次，平均 ${modelCallSummary.averageLatencyMs}ms$tokenText"
        }
        return "最近 $taskCount 个任务：$resultText$runningText · 步骤 $totalSteps · $modelText"
    }
}

object RecentTaskPerformanceSummaryBuilder {
    fun summarize(
        history: List<TaskHistoryEntry>,
        sessions: List<SessionTrace>
    ): RecentTaskPerformanceSummary {
        val modelSummary = summarizeModelCalls(sessions)
        return RecentTaskPerformanceSummary(
            taskCount = history.size,
            finishedCount = history.count { it.status != TaskHistoryStatus.RUNNING },
            successCount = history.count { it.status == TaskHistoryStatus.SUCCEEDED },
            failedCount = history.count { it.status == TaskHistoryStatus.FAILED },
            stoppedCount = history.count { it.status == TaskHistoryStatus.STOPPED },
            runningCount = history.count { it.status == TaskHistoryStatus.RUNNING },
            totalSteps = history.sumOf { it.totalSteps },
            modelCallSummary = modelSummary
        )
    }

    private fun summarizeModelCalls(sessions: List<SessionTrace>): ModelCallSummary {
        val summaries = sessions
            .map(ModelCallSummaryBuilder::summarize)
            .filterNot { it.isEmpty() }
        if (summaries.isEmpty()) {
            return ModelCallSummary(
                callCount = 0,
                totalLatencyMs = 0L,
                averageLatencyMs = 0L,
                requestChars = 0,
                responseChars = 0
            )
        }

        val callCount = summaries.sumOf { it.callCount }
        val totalLatency = summaries.sumOf { it.totalLatencyMs }
        return ModelCallSummary(
            callCount = callCount,
            totalLatencyMs = totalLatency,
            averageLatencyMs = totalLatency / callCount,
            requestChars = summaries.sumOf { it.requestChars },
            responseChars = summaries.sumOf { it.responseChars },
            promptTokens = sumNullable(summaries.map { it.promptTokens }),
            completionTokens = sumNullable(summaries.map { it.completionTokens }),
            totalTokens = sumNullable(summaries.map { it.totalTokens })
        )
    }

    private fun sumNullable(values: List<Int?>): Int? {
        val present = values.filterNotNull()
        if (present.isEmpty()) {
            return null
        }
        return present.sum()
    }
}
