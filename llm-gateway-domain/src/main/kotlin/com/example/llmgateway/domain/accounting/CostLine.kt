package com.example.llmgateway.domain.accounting

import java.math.BigDecimal

data class CostLine(
    val key: UsageKey,
    val quantity: Long?,
    val unitPriceUsd: BigDecimal?,
    val amount: BigDecimal?,
    val status: CostStatus,
    val source: CostSource,
) {
    init {
        require(key.type != UsageType.REASONING_OUTPUT_TOKENS) { "reasoning must not be charged twice" }
        require(quantity == null || quantity >= 0) { "cost quantity must not be negative" }
        require(unitPriceUsd == null || unitPriceUsd.signum() >= 0) { "unit price must not be negative" }
        require(amount == null || amount.signum() >= 0) { "cost line must not be negative" }
        require((amount == null) == (status == CostStatus.UNKNOWN)) { "unknown line must have null amount" }
    }
    val unit: UsageUnit get() = key.type.unit
}
