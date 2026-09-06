package com.example.llmgateway.domain.model


data class Usage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
) {
    val totalTokens: Int get() = inputTokens + outputTokens
}
