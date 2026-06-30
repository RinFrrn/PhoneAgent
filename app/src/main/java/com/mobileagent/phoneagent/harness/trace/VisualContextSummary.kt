package com.mobileagent.phoneagent.harness.trace

import com.mobileagent.phoneagent.harness.observe.Observation
import java.util.Locale

data class VisualContextSummary(
    val observationCount: Int,
    val imageObservationCount: Int,
    val stepsWithImages: Int,
    val totalImageChars: Int,
    val maxImageChars: Int,
    val textChars: Int,
    val captureFailureCount: Int
) {
    fun isEmpty(): Boolean = observationCount == 0

    fun hasImages(): Boolean = imageObservationCount > 0

    fun warnings(): List<String> {
        val warnings = mutableListOf<String>()
        if (captureFailureCount > 0) {
            warnings += "截图失败 $captureFailureCount 次"
        }
        if (maxImageChars >= LARGE_SINGLE_IMAGE_CHARS) {
            warnings += "单次截图上下文偏大"
        }
        if (totalImageChars >= LARGE_TOTAL_IMAGE_CHARS) {
            warnings += "累计图片上下文偏大"
        }
        return warnings
    }

    fun toDisplayText(): String {
        if (isEmpty()) {
            return "视觉上下文: 未记录"
        }
        if (!hasImages() && captureFailureCount == 0) {
            return "视觉上下文: 无图片输入 · 文本 ${textChars} 字符"
        }
        val warningText = warnings().takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = " · ", separator = " · ")
            .orEmpty()
        return "视觉上下文: 图片 $imageObservationCount 次/${stepsWithImages} 步 · " +
            "图片 ${formatChars(totalImageChars)} · 文本 ${textChars} 字符$warningText"
    }

    private fun formatChars(chars: Int): String {
        if (chars < 1024) {
            return "$chars 字符"
        }
        val kib = chars / 1024.0
        if (kib < 1024.0) {
            return String.format(Locale.US, "%.1fK 字符", kib)
        }
        val mib = kib / 1024.0
        return String.format(Locale.US, "%.1fM 字符", mib)
    }

    private companion object {
        const val LARGE_SINGLE_IMAGE_CHARS = 2_000_000
        const val LARGE_TOTAL_IMAGE_CHARS = 8_000_000
    }
}

object VisualContextSummaryBuilder {
    fun summarize(session: SessionTrace): VisualContextSummary {
        val observations = session.steps.flatMap { step ->
            listOfNotNull(step.observationBefore, step.observationAfter)
        }
        val imageLengthsByStep = session.steps.map { step ->
            listOfNotNull(step.observationBefore, step.observationAfter)
                .flatMap(::imageLengths)
        }
        val imageLengths = observations.flatMap(::imageLengths)
        val textChars = observations.sumOf { observation ->
            observation.contentItems.sumOf { item -> item.text?.length ?: 0 }
        }
        val captureFailures = observations.count { observation ->
            observation.failureMessage?.contains("截图") == true
        }
        return VisualContextSummary(
            observationCount = observations.size,
            imageObservationCount = imageLengths.size,
            stepsWithImages = imageLengthsByStep.count { it.isNotEmpty() },
            totalImageChars = imageLengths.sum(),
            maxImageChars = imageLengths.maxOrNull() ?: 0,
            textChars = textChars,
            captureFailureCount = captureFailures
        )
    }

    private fun imageLengths(observation: Observation): List<Int> {
        return observation.contentItems
            .filter { it.type == "image_url" }
            .mapNotNull { it.imageUrl?.url?.length }
    }
}
