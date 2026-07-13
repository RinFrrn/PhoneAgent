package com.mobileagent.phoneagent.harness.runtime

import com.mobileagent.phoneagent.agent.Mode
import com.mobileagent.phoneagent.model.ModelConfig
import com.mobileagent.phoneagent.model.ModelProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelModeAdvisorTest {
    @Test
    fun visualModeWarnsForLikelyTextOnlyModel() {
        val check = ModelModeAdvisor.readinessCheck(
            config = config(ModelProvider.OLLAMA, "deepseek-v3.1:671b-cloud"),
            mode = Mode.VISION
        )

        requireNotNull(check)
        assertTrue(check.detail.contains("需要模型理解截图"))
        assertTrue(check.detail.contains("deepseek-v3.1"))
    }

    @Test
    fun visualModeAllowsKnownMultimodalModel() {
        val check = ModelModeAdvisor.readinessCheck(
            config = config(ModelProvider.GLM, "glm-4.5v"),
            mode = Mode.HYBRID
        )

        assertNull(check)
        assertTrue(ModelModeAdvisor.isLikelyVisionCapable(config(ModelProvider.QWEN, "qwen-vl-max")))
        assertTrue(ModelModeAdvisor.isLikelyVisionCapable(config(ModelProvider.OPENAI, "gpt-4o")))
    }

    @Test
    fun accessibilityModeDoesNotNeedVisionModel() {
        val check = ModelModeAdvisor.readinessCheck(
            config = config(ModelProvider.OLLAMA, "llama3.1"),
            mode = Mode.ACCESSIBILITY
        )

        assertNull(check)
        assertFalse(ModelModeAdvisor.isLikelyVisionCapable(config(ModelProvider.OLLAMA, "llama3.1")))
    }

    private fun config(provider: ModelProvider, modelName: String): ModelConfig {
        return ModelConfig(
            id = "model",
            name = modelName,
            providerName = provider.name,
            baseUrl = provider.defaultBaseUrl.ifBlank { "https://example.test" },
            modelName = modelName,
            apiKey = if (provider.requiresApiKey) "key" else "",
            temperature = 0.1f,
            topP = 0.85f,
            createdAt = 1L,
            updatedAt = 1L
        )
    }
}
