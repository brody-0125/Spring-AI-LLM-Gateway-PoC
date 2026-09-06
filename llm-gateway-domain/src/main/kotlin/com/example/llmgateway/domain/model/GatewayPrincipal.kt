package com.example.llmgateway.domain.model

data class GatewayPrincipal(
    val caller: String,
    val tenant: String,
    val administrator: Boolean = false,
)
