package com.example.llmgateway.domain.model


data class ProviderResponse(
    val text: String,
    val usage: Usage = Usage(),
    val finishReason: String? = null,
)
