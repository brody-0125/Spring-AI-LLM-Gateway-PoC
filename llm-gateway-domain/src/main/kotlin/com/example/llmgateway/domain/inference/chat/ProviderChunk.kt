package com.example.llmgateway.domain.inference.chat

import com.example.llmgateway.domain.accounting.Usage

data class ProviderChunk(
    val text: String,
    val finishReason: String? = null,
    val usage: Usage? = null,
    val providerRequestId: String? = null,
)
