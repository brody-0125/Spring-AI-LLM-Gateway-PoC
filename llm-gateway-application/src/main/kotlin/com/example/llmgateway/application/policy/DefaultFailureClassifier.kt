package com.example.llmgateway.application.policy

import com.example.llmgateway.domain.model.ErrorCategory
import com.example.llmgateway.domain.model.FailureClass
import com.example.llmgateway.domain.model.GatewayException
import com.example.llmgateway.domain.model.ProviderException
import java.net.SocketTimeoutException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

class DefaultFailureClassifier : FailureClassifier {
    override fun classify(error: Throwable): FailureClass {
        val cause = unwrap(error)
        if (cause is GatewayException) return cause.error.category.toFailureClass()
        if (cause is ProviderException) {
            return when (cause.statusCode) {
                401, 403 -> FailureClass.AUTHENTICATION
                408 -> FailureClass.TRANSIENT
                429 -> FailureClass.RATE_LIMITED
                400 -> if (isContextWindowError(cause)) {
                    FailureClass.CONTEXT_WINDOW
                } else {
                    FailureClass.INVALID_REQUEST
                }
                in 500..599 -> FailureClass.TRANSIENT
                else -> FailureClass.UNKNOWN
            }
        }
        return when (cause) {
            is TimeoutException, is SocketTimeoutException -> FailureClass.TRANSIENT
            else -> FailureClass.UNKNOWN
        }
    }

    private fun isContextWindowError(error: ProviderException): Boolean =
        listOfNotNull(error.providerCode, error.message).any { detail ->
            listOf("context", "maximum token", "max token", "token limit")
                .any { marker -> detail.contains(marker, ignoreCase = true) }
        }

    private fun unwrap(error: Throwable): Throwable {
        var current = error
        while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
            current = current.cause!!
        }
        return current
    }
}

private fun ErrorCategory.toFailureClass(): FailureClass = when (this) {
    ErrorCategory.CALLER_FIXABLE -> FailureClass.INVALID_REQUEST
    ErrorCategory.ENTITLEMENT -> FailureClass.AUTHENTICATION
    ErrorCategory.TRANSIENT -> FailureClass.TRANSIENT
    ErrorCategory.GATEWAY_FAULT -> FailureClass.UNKNOWN
}
