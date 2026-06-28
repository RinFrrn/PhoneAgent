package com.mobileagent.phoneagent.model

data class ModelConfig(
    val id: String,
    val name: String,
    val providerName: String,
    val baseUrl: String,
    val modelName: String,
    val apiKey: String,
    val temperature: Float,
    val topP: Float,
    val createdAt: Long,
    val updatedAt: Long
) {
    val provider: ModelProvider
        get() = ModelProvider.fromString(providerName)

    val displayName: String
        get() = name.ifBlank { "${provider.displayName} · $modelName" }

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() &&
            modelName.isNotBlank() &&
            (!provider.requiresApiKey || apiKey.isNotBlank())

    companion object {
        fun default(now: Long = System.currentTimeMillis()): ModelConfig {
            val provider = ModelProvider.OLLAMA
            return ModelConfig(
                id = "model_$now",
                name = provider.defaultModelName,
                providerName = provider.name,
                baseUrl = provider.defaultBaseUrl,
                modelName = provider.defaultModelName,
                apiKey = "",
                temperature = 0.1f,
                topP = 0.85f,
                createdAt = now,
                updatedAt = now
            )
        }
    }
}
