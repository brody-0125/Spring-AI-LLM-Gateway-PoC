package com.example.llmgateway.domain.model


class GatewayException(
    val error: GatewayError,
    cause: Throwable? = null,
) : RuntimeException(error.message, cause)
