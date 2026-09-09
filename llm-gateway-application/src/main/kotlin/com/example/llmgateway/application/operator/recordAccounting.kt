package com.example.llmgateway.application.operator

import com.example.llmgateway.core.primitive.RequestId
import com.example.llmgateway.domain.error.ErrorCategory
import com.example.llmgateway.domain.error.GatewayError
import com.example.llmgateway.domain.error.GatewayException

/** A failed durable write must never trigger another model invocation. */
internal inline fun recordAccounting(requestId: RequestId, write: () -> Unit) {
    try {
        write()
    } catch (error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        throw GatewayException(
            GatewayError(
                type = "outcome_unknown",
                code = "OUTCOME_UNKNOWN",
                category = ErrorCategory.GATEWAY_FAULT,
                retryable = false,
                message = "The gateway could not confirm durable completion; do not repeat this generation request",
                requestId = requestId,
            ),
            error,
        )
    }
}
