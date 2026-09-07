package com.example.llmgateway.domain.model

import java.math.BigDecimal
import java.math.RoundingMode

class CostCalculator(
    private val scale: Int = 12,
) {
    init {
        require(scale >= 2) { "cost scale must be at least 2" }
    }

    fun calculate(usage: Usage, pricing: PricingSnapshot): Cost {
        val warnings = buildSet {
            if (!usage.available) add(CostWarning.USAGE_UNAVAILABLE)
            if (usage.cacheReadInputTokens + usage.cacheWriteInputTokens > usage.inputTokens) {
                add(CostWarning.INCONSISTENT_USAGE)
            }
            if (usage.regularInputTokens > 0 && pricing.inputCostPerTokenUsd == null) {
                add(CostWarning.MISSING_INPUT_PRICE)
            }
            if (usage.outputTokens > 0 && pricing.outputCostPerTokenUsd == null) {
                add(CostWarning.MISSING_OUTPUT_PRICE)
            }
            if (usage.cacheReadInputTokens > 0 && pricing.cacheReadInputCostPerTokenUsd == null) {
                add(CostWarning.MISSING_CACHE_READ_PRICE)
            }
            if (usage.cacheWriteInputTokens > 0 && pricing.cacheWriteInputCostPerTokenUsd == null) {
                add(CostWarning.MISSING_CACHE_WRITE_PRICE)
            }
        }

        val regularInputCost = multiply(usage.regularInputTokens, pricing.inputCostPerTokenUsd)
        val cacheReadCost = multiply(usage.cacheReadInputTokens, pricing.cacheReadInputCostPerTokenUsd)
        val cacheWriteCost = multiply(usage.cacheWriteInputTokens, pricing.cacheWriteInputCostPerTokenUsd)
        val outputCost = multiply(usage.outputTokens, pricing.outputCostPerTokenUsd)
        val total = regularInputCost + cacheReadCost + cacheWriteCost + outputCost
        val status = when {
            CostWarning.USAGE_UNAVAILABLE in warnings -> CostStatus.UNKNOWN
            warnings.isNotEmpty() -> CostStatus.PARTIAL
            else -> CostStatus.REPORTED
        }

        return Cost(
            usd = total,
            status = status,
            pricingVersion = pricing.version,
            inputUsd = regularInputCost,
            outputUsd = outputCost,
            cacheReadUsd = cacheReadCost,
            cacheWriteUsd = cacheWriteCost,
            warnings = warnings,
        )
    }

    private fun multiply(tokens: Long, price: BigDecimal?): BigDecimal =
        if (tokens == 0L || price == null) {
            BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP)
        } else {
            price.multiply(BigDecimal.valueOf(tokens)).setScale(scale, RoundingMode.HALF_UP)
        }
}
