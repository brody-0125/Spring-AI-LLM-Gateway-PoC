package com.example.llmgateway.application.policy

import com.example.llmgateway.domain.model.ErrorCategory
import com.example.llmgateway.domain.model.FailureClass

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
        FailureClass.TRANSIENT, FailureClass.RATE_LIMITED -> true
        else -> false
    }

    fun category(failure: FailureClass): ErrorCategory = when (failure) {
        FailureClass.AUTHENTICATION -> ErrorCategory.ENTITLEMENT
        FailureClass.INVALID_REQUEST, FailureClass.CONTEXT_WINDOW, FailureClass.CONTENT_POLICY ->
            ErrorCategory.CALLER_FIXABLE
        FailureClass.TRANSIENT, FailureClass.RATE_LIMITED -> ErrorCategory.TRANSIENT
        FailureClass.UNKNOWN -> ErrorCategory.GATEWAY_FAULT
    }

    fun errorType(failure: FailureClass): String = when (failure) {
        FailureClass.CONTEXT_WINDOW -> "context_window_exceeded"
        FailureClass.RATE_LIMITED -> "rate_limited"
        FailureClass.AUTHENTICATION -> "provider_authentication_failed"
        FailureClass.INVALID_REQUEST -> "provider_invalid_request"
        FailureClass.CONTENT_POLICY -> "content_policy_rejected"
        FailureClass.TRANSIENT -> "provider_unavailable"
        FailureClass.UNKNOWN -> "provider_error"
    }

    fun clientMessage(failure: FailureClass): String = when (failure) {
        FailureClass.CONTEXT_WINDOW -> "The request exceeds the model context window"
        FailureClass.RATE_LIMITED -> "The provider rate limit was exceeded"
        FailureClass.AUTHENTICATION -> "Provider authentication failed"
        FailureClass.INVALID_REQUEST -> "The provider rejected the request"
        FailureClass.CONTENT_POLICY -> "The provider rejected the request due to content policy"
        FailureClass.TRANSIENT -> "The provider is temporarily unavailable"
        FailureClass.UNKNOWN -> "The provider request failed"
    }
}
