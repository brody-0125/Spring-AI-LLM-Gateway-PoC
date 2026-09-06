package com.example.llmgateway.domain.model


sealed interface GatewayEvent {
    data class Delta(
        val id: String,
        val text: String,
        val model: String = "",
        val createdAtEpochSeconds: Long = 0,
    ) : GatewayEvent

    data class Complete(
        val id: String,
        val usage: Usage,
        val model: String = "",
        val createdAtEpochSeconds: Long = 0,
        val finishReason: String = "stop",
    ) : GatewayEvent
}
