package com.example.llmgateway.domain.error

enum class ErrorCategory {
    CALLER_FIXABLE,
    ENTITLEMENT,
    TRANSIENT,
    GATEWAY_FAULT,
}
