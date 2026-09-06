package com.example.llmgateway.domain.model


data class ProviderChunk(
    val text: String,
    val finishReason: String? = null,
    val usage: Usage? = null,
)
