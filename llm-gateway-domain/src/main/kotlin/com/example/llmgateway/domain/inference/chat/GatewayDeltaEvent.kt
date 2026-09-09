package com.example.llmgateway.domain.inference.chat

data class GatewayDeltaEvent(
    val id: String,
    val text: String,
    val model: String = "",
    val createdAtEpochSeconds: Long = 0,
) : GatewayEvent
