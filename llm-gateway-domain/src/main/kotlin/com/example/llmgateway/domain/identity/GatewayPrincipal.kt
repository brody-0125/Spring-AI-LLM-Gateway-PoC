package com.example.llmgateway.domain.identity

data class GatewayPrincipal(
    val caller: String,
    val tenant: String,
    val administrator: Boolean = false,
)
