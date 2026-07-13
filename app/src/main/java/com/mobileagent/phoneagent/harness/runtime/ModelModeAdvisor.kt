package com.mobileagent.phoneagent.harness.runtime

import com.mobileagent.phoneagent.agent.Mode
import com.mobileagent.phoneagent.model.ModelConfig
import com.mobileagent.phoneagent.model.ModelProvider

object ModelModeAdvisor {
    fun readinessCheck(config: ModelConfig, mode: Mode): ReadinessCheck? {
        if (!config.isConfigured) {
            return null
        }
        val needsVision = mode == Mode.VISION || mode == Mode.HYBRID
        if (!needsVision) {
            return null
        }
        if (isLikelyVisionCapable(config)) {
            return null
        }

        return ReadinessCheck(
            id = "model_mode_fit",
            severity = ReadinessSeverity.WARNING,
            title = "模型可能不适合当前模式",
            detail = "当前为 $mode，需要模型理解截图；${config.provider.displayName} · ${config.modelName} 看起来不是明确的视觉/多模态模型。建议切换到 GLM-v、Qwen-VL、GPT-4o、Claude 3+、Gemini 或 AutoGLM Phone，或改用无障碍模式。"
        )
    }

    fun isLikelyVisionCapable(config: ModelConfig): Boolean {
        val model = config.modelName.trim().lowercase()
        if (model.isBlank()) {
            return false
        }
        return when (config.provider) {
            ModelProvider.GLM -> containsAny(model, "glm") && containsAny(model, "v", "vision", "autoglm", "phone")
            ModelProvider.QWEN -> containsAny(model, "vl", "vision", "omni")
            ModelProvider.OPENAI -> containsAny(model, "gpt-4o", "gpt-4.1", "gpt-4v", "vision", "o3", "o4")
            ModelProvider.ANTHROPIC -> containsAny(model, "claude-3", "claude-4", "sonnet", "opus", "haiku")
            ModelProvider.GOOGLE -> containsAny(model, "gemini")
            ModelProvider.MIMO -> true
            ModelProvider.OLLAMA,
            ModelProvider.CUSTOM -> containsAny(model, "vl", "vision", "llava", "bakllava", "minicpm-v", "qwen2-vl", "qwen-vl", "moondream")
        }
    }

    private fun containsAny(text: String, vararg tokens: String): Boolean {
        return tokens.any { token -> text.contains(token) }
    }
}
