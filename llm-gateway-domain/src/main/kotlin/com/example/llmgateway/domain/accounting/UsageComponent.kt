package com.example.llmgateway.domain.accounting

data class UsageComponent(
    val key: UsageKey,
    val quantity: Long?,
    val source: UsageSource = if (quantity == null) UsageSource.UNKNOWN else UsageSource.PROVIDER_REPORTED,
) {
    init {
        require(quantity == null || quantity >= 0) { "usage quantity must not be negative" }
        require((quantity == null) == (source == UsageSource.UNKNOWN)) {
            "unknown measurement must have a null quantity"
        }
    }
    val unit: UsageUnit get() = key.type.unit
}
