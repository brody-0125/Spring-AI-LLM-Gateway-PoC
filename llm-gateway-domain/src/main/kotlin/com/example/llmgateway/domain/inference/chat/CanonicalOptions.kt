package com.example.llmgateway.domain.inference.chat

data class CanonicalOptions(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val maxCompletionTokens: Int? = null,
    val topP: Double? = null,
    val stop: List<String>? = null,
)
