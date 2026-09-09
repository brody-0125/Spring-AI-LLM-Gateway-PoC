package com.example.llmgateway.domain.accounting

import java.math.BigDecimal

data class ComponentPrice(val key: UsageKey, val usdPerUnit: BigDecimal?) {
    init {
        require(key.type != UsageType.REASONING_OUTPUT_TOKENS) {
            "reasoning is included in output pricing and cannot have a second price"
        }
        usdPerUnit?.let {
            require(it.signum() >= 0) { "pricing must not be negative" }
            require(it.stripTrailingZeros().scale() <= 18) { "pricing exceeds supported fractional precision" }
            require(it < BigDecimal("1000000")) { "pricing exceeds supported numeric range" }
        }
    }
}
