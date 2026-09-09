package com.example.llmgateway.domain.inference.chat

import com.example.llmgateway.core.primitive.ModelGroup


data class CanonicalChatRequest(
    val modelGroup: ModelGroup,
    val messages: List<CanonicalMessage>,
    val options: CanonicalOptions = CanonicalOptions(),
    val stream: Boolean = false,
)
