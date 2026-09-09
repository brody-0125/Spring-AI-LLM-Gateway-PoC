package com.example.llmgateway.domain.error

class GatewayException(
    val error: GatewayError,
    cause: Throwable? = null,
) : RuntimeException(error.message, cause)
