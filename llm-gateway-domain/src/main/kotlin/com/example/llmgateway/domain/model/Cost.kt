package com.example.llmgateway.domain.model

import java.math.BigDecimal

data class Cost(
    val usd: BigDecimal = BigDecimal.ZERO,
) {
    init {
        require(usd >= BigDecimal.ZERO) { "cost must not be negative" }
    }
}
