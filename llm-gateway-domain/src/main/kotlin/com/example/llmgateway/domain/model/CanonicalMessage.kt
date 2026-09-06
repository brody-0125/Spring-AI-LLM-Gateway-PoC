package com.example.llmgateway.domain.model
import com.example.llmgateway.core.primitive.MessageRole


data class CanonicalMessage(
    val role: MessageRole,
    val content: String,
)
