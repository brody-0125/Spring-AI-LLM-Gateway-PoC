package com.example.llmgateway.domain.inference.chat

import com.example.llmgateway.domain.accounting.Usage
import java.time.Instant


data class GatewayResponse(
    val id: String,
    val model: String,
    val text: String,
    val usage: Usage,
    val createdAtEpochSeconds: Long = Instant.now().epochSecond,
    val finishReason: String? = null,
)
