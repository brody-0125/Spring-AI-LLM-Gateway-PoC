package com.example.llmgateway.domain.error

enum class FailureClass {
    TRANSIENT,
    RATE_LIMITED,
    GATEWAY_TIMEOUT,
    AUTHENTICATION,
    INVALID_REQUEST,
    CONTEXT_WINDOW,
    CONTENT_POLICY,
    UNKNOWN,
}
