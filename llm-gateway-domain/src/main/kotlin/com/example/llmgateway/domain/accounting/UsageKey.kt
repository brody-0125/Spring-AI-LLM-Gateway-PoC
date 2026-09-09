package com.example.llmgateway.domain.accounting

data class UsageKey(val type: UsageType, val variant: String = "") {
    init {
        require(variant.isEmpty() || variant.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}"))) {
            "usage variant must be a bounded profile identifier"
        }
        require(variant.isEmpty() || type !in setOf(
            UsageType.INPUT_TOKENS, UsageType.OUTPUT_TOKENS, UsageType.REASONING_OUTPUT_TOKENS,
        )) { "canonical token totals cannot have variants" }
    }
}
