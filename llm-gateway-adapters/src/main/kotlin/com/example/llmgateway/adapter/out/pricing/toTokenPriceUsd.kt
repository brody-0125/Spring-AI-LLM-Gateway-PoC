package com.example.llmgateway.adapter.out.pricing

import java.math.BigDecimal
import java.math.RoundingMode

/** Missing stays unknown; zero is explicitly free. Reject precision loss instead of rounding to free. */
internal fun BigDecimal?.toTokenPriceUsd(): BigDecimal? = this?.let {
    require(it.signum() >= 0) { "configured prices must not be negative" }
    it.movePointLeft(3).setScale(18, RoundingMode.UNNECESSARY).also { price ->
        require(price.precision() <= 24) { "configured price exceeds the supported numeric range" }
    }
}
