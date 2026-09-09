package com.example.llmgateway.domain.accounting

import java.math.BigDecimal

object BudgetSettlementPolicy {
    /** Null retains the hold: a rate-card estimate and estimated measurements are different. */
    fun amount(usage: Usage, cost: Cost): BigDecimal? {
        if (cost.source == CostSource.PROVIDER_REPORTED && cost.status == CostStatus.REPORTED) return cost.usd
        if (cost.source != CostSource.RATE_CARD || cost.status != CostStatus.ESTIMATED || cost.warnings.isNotEmpty()) return null
        val required = listOf(UsageType.INPUT_TOKENS, UsageType.OUTPUT_TOKENS,
            UsageType.CACHE_READ_INPUT_TOKENS, UsageType.CACHE_WRITE_INPUT_TOKENS)
        return cost.usd.takeIf {
            required.all { type -> usage.components.any { component ->
                component.key == UsageKey(type) && component.quantity != null && component.source == UsageSource.PROVIDER_REPORTED
            } }
        }
    }
}
