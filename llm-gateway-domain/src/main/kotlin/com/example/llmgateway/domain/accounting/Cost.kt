package com.example.llmgateway.domain.accounting

import java.math.BigDecimal
import java.util.Collections

class Cost(
    val usd: BigDecimal = BigDecimal.ZERO,
    val status: CostStatus = CostStatus.UNKNOWN,
    val pricingVersion: String? = null,
    val inputUsd: BigDecimal = BigDecimal.ZERO,
    val outputUsd: BigDecimal = BigDecimal.ZERO,
    val cacheReadUsd: BigDecimal = BigDecimal.ZERO,
    val cacheWriteUsd: BigDecimal = BigDecimal.ZERO,
    warnings: Set<CostWarning> = emptySet(),
    val source: CostSource = CostSource.UNKNOWN,
    lines: List<CostLine> = emptyList(),
) {
    val warnings: Set<CostWarning> = Collections.unmodifiableSet(warnings.toSet())
    val lines: List<CostLine> = Collections.unmodifiableList(lines.toList())
    init {
        require(usd >= BigDecimal.ZERO) { "cost must not be negative" }
        require(inputUsd >= BigDecimal.ZERO) { "input cost must not be negative" }
        require(outputUsd >= BigDecimal.ZERO) { "output cost must not be negative" }
        require(cacheReadUsd >= BigDecimal.ZERO) { "cache read cost must not be negative" }
        require(cacheWriteUsd >= BigDecimal.ZERO) { "cache write cost must not be negative" }
        require(this.lines.map { it.key }.distinct().size == this.lines.size) { "cost line keys must be unique" }
        if (this.lines.isNotEmpty()) {
            require(usd.compareTo(this.lines.mapNotNull { it.amount }.fold(BigDecimal.ZERO, BigDecimal::add)) == 0) {
                "cost must equal the known line subtotal"
            }
        }
    }

    /** Unknown is not a zero charge. PARTIAL exposes only the known subtotal. */
    val amount: BigDecimal? get() = usd.takeUnless { status == CostStatus.UNKNOWN }

    override fun equals(other: Any?): Boolean = other is Cost &&
        usd == other.usd && status == other.status && pricingVersion == other.pricingVersion &&
        inputUsd == other.inputUsd && outputUsd == other.outputUsd &&
        cacheReadUsd == other.cacheReadUsd && cacheWriteUsd == other.cacheWriteUsd &&
        warnings == other.warnings && source == other.source && lines == other.lines
    override fun hashCode(): Int = listOf(usd, status, pricingVersion, inputUsd, outputUsd,
        cacheReadUsd, cacheWriteUsd, warnings, source, lines).hashCode()
    override fun toString(): String = "Cost(amount=$amount, status=$status, source=$source, lines=$lines)"
}
