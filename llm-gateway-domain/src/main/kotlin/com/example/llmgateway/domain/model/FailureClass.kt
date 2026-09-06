package com.example.llmgateway.domain.model


enum class FailureClass {
    TRANSIENT,
    RATE_LIMITED,
    AUTHENTICATION,
    INVALID_REQUEST,
    CONTEXT_WINDOW,
    CONTENT_POLICY,
    UNKNOWN,
}
