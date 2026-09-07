package com.example.llmgateway.domain.model

import java.math.BigDecimal
import java.time.Instant

data class PricingSnapshot(
    val version: String,
    val inputCostPerTokenUsd: BigDecimal?,
    val outputCostPerTokenUsd: BigDecimal?,
    val cacheReadInputCostPerTokenUsd: BigDecimal? = null,
    val cacheWriteInputCostPerTokenUsd: BigDecimal? = null,
    val effectiveFrom: Instant? = null,
) {
    init {
        listOf(
            inputCostPerTokenUsd,
            outputCostPerTokenUsd,
            cacheReadInputCostPerTokenUsd,
            cacheWriteInputCostPerTokenUsd,
        ).filterNotNull().forEach { price ->
            require(price >= BigDecimal.ZERO) { "pricing must not be negative" }
        }
    }
}
