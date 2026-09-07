package com.example.llmgateway.domain.model

import java.math.BigDecimal

data class Cost(
    val usd: BigDecimal = BigDecimal.ZERO,
    val status: CostStatus = CostStatus.UNKNOWN,
    val pricingVersion: String? = null,
    val inputUsd: BigDecimal = BigDecimal.ZERO,
    val outputUsd: BigDecimal = BigDecimal.ZERO,
    val cacheReadUsd: BigDecimal = BigDecimal.ZERO,
    val cacheWriteUsd: BigDecimal = BigDecimal.ZERO,
    val warnings: Set<CostWarning> = emptySet(),
) {
    init {
        require(usd >= BigDecimal.ZERO) { "cost must not be negative" }
        require(inputUsd >= BigDecimal.ZERO) { "input cost must not be negative" }
        require(outputUsd >= BigDecimal.ZERO) { "output cost must not be negative" }
        require(cacheReadUsd >= BigDecimal.ZERO) { "cache read cost must not be negative" }
        require(cacheWriteUsd >= BigDecimal.ZERO) { "cache write cost must not be negative" }
    }
}
