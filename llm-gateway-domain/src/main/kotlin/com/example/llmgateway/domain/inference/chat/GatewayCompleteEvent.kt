package com.example.llmgateway.domain.inference.chat

import com.example.llmgateway.domain.accounting.Usage

data class GatewayCompleteEvent(
    val id: String,
    val usage: Usage,
    val model: String = "",
    val createdAtEpochSeconds: Long = 0,
    val finishReason: String = "stop",
) : GatewayEvent
