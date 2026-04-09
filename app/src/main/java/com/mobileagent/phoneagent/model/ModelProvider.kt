/**
 * AI 模型服务商配置
 * 
 * 项目地址: https://github.com/MR-MaoJiu/PhoneAgent
 * 
 * 定义支持的 AI 服务商及其配置信息
 */
package com.mobileagent.phoneagent.model

/**
 * AI 模型服务商配置
 */
enum class ModelProvider(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModelName: String,
    val requiresApiKey: Boolean,
    val imageFormat: ImageFormat,
    val responseFormat: ResponseFormat
) {
    MIMO(
        displayName = "MOMO (小米AI)",
        defaultBaseUrl = "https://api.xiaomimimo.com/v1",
        defaultModelName = "mimo-v2-pro",
        requiresApiKey = true,
        imageFormat = ImageFormat.BASE64,
        responseFormat = ResponseFormat.OPENAI_COMPATIBLE
    ),
    OLLAMA(
        displayName = "Ollama",
        defaultBaseUrl = "http://127.0.0.1:11434/v1",
        defaultModelName = "deepseek-v3.1:671b-cloud",
        requiresApiKey = false,
        imageFormat = ImageFormat.DATA_URL,
        responseFormat = ResponseFormat.OPENAI_COMPATIBLE
    ),
    OPENAI(
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModelName = "gpt-4o",
        requiresApiKey = true,
        imageFormat = ImageFormat.DATA_URL,
        responseFormat = ResponseFormat.OPENAI_COMPATIBLE
    ),
    ANTHROPIC(
        displayName = "Anthropic (Claude)",
        defaultBaseUrl = "https://api.anthropic.com/v1",
        defaultModelName = "claude-3-5-sonnet-20241022",
        requiresApiKey = true,
        imageFormat = ImageFormat.BASE64,
        responseFormat = ResponseFormat.ANTHROPIC
    ),
    GOOGLE(
        displayName = "Google (Gemini)",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
        defaultModelName = "gemini-pro-vision",
        requiresApiKey = true,
        imageFormat = ImageFormat.BASE64,
        responseFormat = ResponseFormat.GOOGLE
    ),
    QWEN(
        displayName = "Qwen (通义千问)",
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModelName = "qwen-vl-max",
        requiresApiKey = true,
        imageFormat = ImageFormat.DATA_URL,
        responseFormat = ResponseFormat.OPENAI_COMPATIBLE
    ),
    GLM(
        displayName = "GLM (智谱AI)",
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
        defaultModelName = "glm-4.5v",
        requiresApiKey = true,
        imageFormat = ImageFormat.DATA_URL,
        responseFormat = ResponseFormat.GLM
    ),
    CUSTOM(
        displayName = "自定义",
        defaultBaseUrl = "",
        defaultModelName = "",
        requiresApiKey = false,
        imageFormat = ImageFormat.DATA_URL,
        responseFormat = ResponseFormat.OPENAI_COMPATIBLE
    );

    companion object {
        fun fromString(name: String): ModelProvider {
            return values().find { it.name.equals(name, ignoreCase = true) || 
                                 it.displayName.equals(name, ignoreCase = true) } 
                ?: CUSTOM
        }
    }
}

/**
 * 图片格式
 */
enum class ImageFormat {
    DATA_URL,  // data:image/png;base64,xxx (OpenAI兼容)
    BASE64     // 纯base64字符串 (Anthropic, Google)
}

/**
 * 响应格式
 */
enum class ResponseFormat {
    OPENAI_COMPATIBLE,  // OpenAI兼容格式
    ANTHROPIC,          // Anthropic格式
    GOOGLE,             // Google格式
    GLM                 // GLM (智谱AI) 格式，支持 thinking 字段
}

