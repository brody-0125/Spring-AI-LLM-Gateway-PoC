package com.example.llmgateway.domain.accounting

import java.math.BigDecimal
import java.time.Instant
import java.util.Collections

class PricingSnapshot(
    val version: String,
    prices: List<ComponentPrice>,
    val effectiveFrom: Instant? = null,
) {
    val prices: List<ComponentPrice> = Collections.unmodifiableList(prices.toList())

    constructor(
        version: String,
        inputCostPerTokenUsd: BigDecimal?,
        outputCostPerTokenUsd: BigDecimal?,
        cacheReadInputCostPerTokenUsd: BigDecimal? = null,
        cacheWriteInputCostPerTokenUsd: BigDecimal? = null,
        effectiveFrom: Instant? = null,
    ) : this(version, listOf(
        ComponentPrice(UsageKey(UsageType.INPUT_TOKENS), inputCostPerTokenUsd),
        ComponentPrice(UsageKey(UsageType.OUTPUT_TOKENS), outputCostPerTokenUsd),
        ComponentPrice(UsageKey(UsageType.CACHE_READ_INPUT_TOKENS), cacheReadInputCostPerTokenUsd),
        ComponentPrice(UsageKey(UsageType.CACHE_WRITE_INPUT_TOKENS), cacheWriteInputCostPerTokenUsd),
    ), effectiveFrom)

    init {
        require(version.isNotBlank()) { "pricing version must be nonblank" }
        require(this.prices.map { it.key }.distinct().size == this.prices.size) { "price keys must be unique" }
    }

    fun price(key: UsageKey): BigDecimal? = prices.firstOrNull { it.key == key }?.usdPerUnit
    val inputCostPerTokenUsd: BigDecimal? get() = price(UsageKey(UsageType.INPUT_TOKENS))
    val outputCostPerTokenUsd: BigDecimal? get() = price(UsageKey(UsageType.OUTPUT_TOKENS))
    val cacheReadInputCostPerTokenUsd: BigDecimal? get() = price(UsageKey(UsageType.CACHE_READ_INPUT_TOKENS))
    val cacheWriteInputCostPerTokenUsd: BigDecimal? get() = price(UsageKey(UsageType.CACHE_WRITE_INPUT_TOKENS))

    override fun equals(other: Any?): Boolean = other is PricingSnapshot &&
        version == other.version && prices == other.prices && effectiveFrom == other.effectiveFrom
    override fun hashCode(): Int = listOf(version, prices, effectiveFrom).hashCode()
    override fun toString(): String = "PricingSnapshot(version=$version, prices=$prices, effectiveFrom=$effectiveFrom)"
}
