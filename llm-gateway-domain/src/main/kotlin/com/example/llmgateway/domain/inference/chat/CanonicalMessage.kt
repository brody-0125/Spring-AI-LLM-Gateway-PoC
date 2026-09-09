package com.example.llmgateway.domain.inference.chat

import com.example.llmgateway.core.primitive.MessageRole


data class CanonicalMessage(
    val role: MessageRole,
    val content: String,
)
