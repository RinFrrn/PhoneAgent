package com.mobileagent.phoneagent.harness.trace

import com.mobileagent.phoneagent.model.ModelCallStats
import java.util.Locale

data class ModelCostEstimate(
    val costUsd: Double,
    val pricingLabel: String
)

object ModelCostEstimator {
    fun estimate(stats: ModelCallStats): ModelCostEstimate? {
        val model = stats.modelName.lowercase(Locale.US)
        if (model.contains("flash") || model.contains("autoglm")) {
            return ModelCostEstimate(costUsd = 0.0, pricingLabel = "free-tier")
        }

        val pricing = pricingFor(model) ?: return null
        val promptTokens = stats.promptTokens ?: return null
        val completionTokens = stats.completionTokens ?: return null
        val cost = promptTokens * pricing.promptUsdPerToken +
            completionTokens * pricing.completionUsdPerToken
        return ModelCostEstimate(costUsd = cost, pricingLabel = pricing.label)
    }

    fun formatUsd(cost: Double): String {
        return "$" + String.format(Locale.US, "%.6f", cost)
    }

    private fun pricingFor(model: String): ModelPricing? {
        return when (model) {
            "glm-4-plus" -> ModelPricing(
                promptUsdPerToken = 0.05 / 1000.0,
                completionUsdPerToken = 0.05 / 1000.0,
                label = "glm-4-plus sample"
            )
            "glm-4-air" -> ModelPricing(
                promptUsdPerToken = 0.001 / 1000.0,
                completionUsdPerToken = 0.001 / 1000.0,
                label = "glm-4-air sample"
            )
            else -> null
        }
    }
}

private data class ModelPricing(
    val promptUsdPerToken: Double,
    val completionUsdPerToken: Double,
    val label: String
)
