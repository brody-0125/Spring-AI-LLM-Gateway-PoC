package com.example.llmgateway.domain.model
import java.time.Instant


data class GatewayResponse(
    val id: String,
    val model: String,
    val text: String,
    val usage: Usage,
    val createdAtEpochSeconds: Long = Instant.now().epochSecond,
    val finishReason: String? = null,
)
