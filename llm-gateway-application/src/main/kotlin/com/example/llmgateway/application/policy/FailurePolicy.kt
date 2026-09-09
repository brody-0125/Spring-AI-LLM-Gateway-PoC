package com.example.llmgateway.application.policy

import com.example.llmgateway.domain.error.ErrorCategory
import com.example.llmgateway.domain.error.FailureClass

class FailurePolicy {
    fun fallbackEligible(failure: FailureClass): Boolean = when (failure) {
        FailureClass.TRANSIENT, FailureClass.RATE_LIMITED, FailureClass.CONTEXT_WINDOW -> true
        else -> false
    }

    fun circuitBreakerEligible(failure: FailureClass): Boolean = when (failure) {
        FailureClass.TRANSIENT, FailureClass.RATE_LIMITED -> true
        else -> false
    }

    fun clientRetryable(failure: FailureClass): Boolean = when (failure) {
        FailureClass.TRANSIENT, FailureClass.RATE_LIMITED, FailureClass.GATEWAY_TIMEOUT -> true
        else -> false
    }

    fun category(failure: FailureClass): ErrorCategory = when (failure) {
        FailureClass.AUTHENTICATION -> ErrorCategory.ENTITLEMENT
        FailureClass.INVALID_REQUEST, FailureClass.CONTEXT_WINDOW, FailureClass.CONTENT_POLICY ->
            ErrorCategory.CALLER_FIXABLE
        FailureClass.TRANSIENT, FailureClass.RATE_LIMITED, FailureClass.GATEWAY_TIMEOUT -> ErrorCategory.TRANSIENT
        FailureClass.UNKNOWN -> ErrorCategory.GATEWAY_FAULT
    }

    fun errorType(failure: FailureClass): String = when (failure) {
        FailureClass.CONTEXT_WINDOW -> "context_window_exceeded"
        FailureClass.RATE_LIMITED -> "rate_limited"
        FailureClass.GATEWAY_TIMEOUT -> "gateway_timeout"
        FailureClass.AUTHENTICATION -> "provider_authentication_failed"
        FailureClass.INVALID_REQUEST -> "provider_invalid_request"
        FailureClass.CONTENT_POLICY -> "content_policy_rejected"
        FailureClass.TRANSIENT -> "provider_unavailable"
        FailureClass.UNKNOWN -> "provider_error"
    }

    fun errorCode(failure: FailureClass): String = when (failure) {
        FailureClass.CONTEXT_WINDOW -> "CONTEXT_WINDOW_EXCEEDED"
        FailureClass.RATE_LIMITED -> "RATE_LIMITED"
        FailureClass.GATEWAY_TIMEOUT -> "GATEWAY_TIMEOUT"
        FailureClass.AUTHENTICATION -> "PROVIDER_AUTHENTICATION_FAILED"
        FailureClass.INVALID_REQUEST -> "PROVIDER_INVALID_REQUEST"
        FailureClass.CONTENT_POLICY -> "CONTENT_POLICY_REJECTED"
        FailureClass.TRANSIENT -> "LLM_PROVIDER_UNAVAILABLE"
        FailureClass.UNKNOWN -> "LLM_PROVIDER_ERROR"
    }

    fun clientMessage(failure: FailureClass): String = when (failure) {
        FailureClass.CONTEXT_WINDOW -> "The request exceeds the model context window"
        FailureClass.RATE_LIMITED -> "The provider rate limit was exceeded"
        FailureClass.GATEWAY_TIMEOUT -> "The gateway request deadline was exceeded"
        FailureClass.AUTHENTICATION -> "Provider authentication failed"
        FailureClass.INVALID_REQUEST -> "The provider rejected the request"
        FailureClass.CONTENT_POLICY -> "The provider rejected the request due to content policy"
        FailureClass.TRANSIENT -> "The provider is temporarily unavailable"
        FailureClass.UNKNOWN -> "The provider request failed"
    }
}
