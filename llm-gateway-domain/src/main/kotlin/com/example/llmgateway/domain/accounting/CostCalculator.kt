package com.example.llmgateway.domain.accounting

import java.math.BigDecimal
import java.math.RoundingMode

class CostCalculator {
    fun calculate(usage: Usage, pricing: PricingSnapshot?): Cost {
        val billable = usage.components.filter { it.key.type != UsageType.REASONING_OUTPUT_TOKENS }
        val cacheTypes = setOf(UsageType.CACHE_READ_INPUT_TOKENS, UsageType.CACHE_WRITE_INPUT_TOKENS)
        val inputKnown = usage.quantity(UsageType.INPUT_TOKENS) != null
        val inconsistent = inputKnown && usage.cacheReadInputTokens + usage.cacheWriteInputTokens > usage.inputTokens
        val unknownCache = usage.components.any { it.key.type in cacheTypes && it.quantity == null }
        val source = if (pricing == null) CostSource.UNKNOWN else CostSource.RATE_CARD
        val lines = billable.map { component ->
            val quantity = if (component.key.type == UsageType.INPUT_TOKENS) {
                usage.regularInputTokens.takeIf { inputKnown && !unknownCache && !inconsistent }
            } else component.quantity
            val price = pricing?.price(component.key)
            val amount = when {
                quantity == null -> null
                quantity == 0L -> BigDecimal.ZERO.setScale(18)
                price == null -> null
                else -> price.multiply(BigDecimal.valueOf(quantity)).setScale(18, RoundingMode.UNNECESSARY)
            }
            CostLine(component.key, quantity, price, amount,
                if (amount == null) CostStatus.UNKNOWN else CostStatus.ESTIMATED, source)
        }
        val warnings = buildSet {
            if (pricing == null) add(CostWarning.PRICING_UNAVAILABLE)
            if (billable.isEmpty() || billable.all { it.quantity == null }) add(CostWarning.USAGE_UNAVAILABLE)
            else if (billable.any { it.quantity == null }) add(CostWarning.PARTIAL_USAGE)
            if (inconsistent) add(CostWarning.INCONSISTENT_USAGE)
            lines.filter { it.quantity != null && it.quantity > 0 && it.unitPriceUsd == null }.forEach {
                add(when (it.key.type) {
                    UsageType.INPUT_TOKENS -> CostWarning.MISSING_INPUT_PRICE
                    UsageType.OUTPUT_TOKENS -> CostWarning.MISSING_OUTPUT_PRICE
                    UsageType.CACHE_READ_INPUT_TOKENS -> CostWarning.MISSING_CACHE_READ_PRICE
                    UsageType.CACHE_WRITE_INPUT_TOKENS -> CostWarning.MISSING_CACHE_WRITE_PRICE
                    else -> CostWarning.MISSING_COMPONENT_PRICE
                })
            }
        }
        val hasKnownCharge = lines.any { it.amount != null && it.quantity != 0L } ||
            (lines.isNotEmpty() && lines.all { it.quantity == 0L })
        val status = when {
            pricing == null || CostWarning.USAGE_UNAVAILABLE in warnings || !hasKnownCharge -> CostStatus.UNKNOWN
            warnings.isNotEmpty() || lines.any { it.amount == null } -> CostStatus.PARTIAL
            else -> CostStatus.ESTIMATED
        }
        fun subtotal(type: UsageType? = null): BigDecimal = lines
            .filter { type == null || it.key.type == type }.mapNotNull { it.amount }
            .fold(BigDecimal.ZERO.setScale(18), BigDecimal::add)
        return Cost(
            usd = subtotal(), status = status, pricingVersion = pricing?.version,
            inputUsd = subtotal(UsageType.INPUT_TOKENS), outputUsd = subtotal(UsageType.OUTPUT_TOKENS),
            cacheReadUsd = subtotal(UsageType.CACHE_READ_INPUT_TOKENS),
            cacheWriteUsd = subtotal(UsageType.CACHE_WRITE_INPUT_TOKENS),
            warnings = warnings, source = source, lines = lines,
        )
    }
}
