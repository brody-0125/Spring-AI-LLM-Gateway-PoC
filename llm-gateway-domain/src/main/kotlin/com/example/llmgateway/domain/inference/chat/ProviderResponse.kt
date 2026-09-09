package com.example.llmgateway.domain.inference.chat

import com.example.llmgateway.domain.accounting.Usage

data class ProviderResponse(
    val text: String,
    val usage: Usage = Usage(emptyList()),
    val finishReason: String? = null,
    val providerRequestId: String? = null,
)
