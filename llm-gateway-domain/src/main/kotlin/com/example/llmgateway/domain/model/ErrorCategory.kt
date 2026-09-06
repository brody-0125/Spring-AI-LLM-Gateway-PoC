package com.example.llmgateway.domain.model


enum class ErrorCategory {
    CALLER_FIXABLE,
    ENTITLEMENT,
    TRANSIENT,
    GATEWAY_FAULT,
}
