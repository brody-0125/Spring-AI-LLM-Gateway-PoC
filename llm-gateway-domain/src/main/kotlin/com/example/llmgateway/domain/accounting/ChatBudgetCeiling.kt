package com.example.llmgateway.domain.accounting

import java.math.BigDecimal

/** Verified maximum billable units for a text Chat profile, including provider-added input. */
data class ChatBudgetCeiling(val inputTokens: Long, val outputTokens: Long) {
    init {
        require(inputTokens > 0 && outputTokens > 0) { "Billable model bounds must be positive" }
    }

    fun reserveUsd(pricing: PricingSnapshot): BigDecimal {
        val supported = setOf(UsageType.INPUT_TOKENS, UsageType.OUTPUT_TOKENS,
            UsageType.CACHE_READ_INPUT_TOKENS, UsageType.CACHE_WRITE_INPUT_TOKENS)
        require(pricing.prices.all { it.key.type in supported && it.key.variant.isEmpty() }) {
            "Chat ceiling does not cover additional billing dimensions"
        }
        val input = requireNotNull(pricing.inputCostPerTokenUsd) { "Input price is unknown" }
        val output = requireNotNull(pricing.outputCostPerTokenUsd) { "Output price is unknown" }
        val cacheRead = requireNotNull(pricing.cacheReadInputCostPerTokenUsd) { "Cache read price is unknown" }
        val cacheWrite = requireNotNull(pricing.cacheWriteInputCostPerTokenUsd) { "Cache write price is unknown" }
        // Gross input already includes cache; reasoning is included in output. No predicted discount.
        return maxOf(input, cacheRead, cacheWrite).multiply(BigDecimal.valueOf(inputTokens))
            .add(output.multiply(BigDecimal.valueOf(outputTokens))).also {
                require(it.stripTrailingZeros().scale() <= 18 && it < BigDecimal("1000000000000")) {
                    "Reservation exceeds the supported exact USD range"
                }
            }
    }
}
