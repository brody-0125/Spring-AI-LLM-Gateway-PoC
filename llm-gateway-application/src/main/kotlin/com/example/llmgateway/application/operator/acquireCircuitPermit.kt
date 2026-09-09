package com.example.llmgateway.application.operator

import com.example.llmgateway.application.port.out.CircuitBreakerPort
import com.example.llmgateway.domain.error.ErrorCategory
import com.example.llmgateway.domain.error.GatewayError
import com.example.llmgateway.domain.error.GatewayException
import com.example.llmgateway.domain.execution.AttemptContext
import com.example.llmgateway.domain.routing.CircuitPermit

internal fun CircuitBreakerPort.acquireCircuitPermit(attempt: AttemptContext): CircuitPermit =
    acquire(attempt.deployment, attempt.attemptId) ?: throw GatewayException(
        GatewayError(
            type = "no_available_deployment",
            code = "LLM_UNAVAILABLE",
            category = ErrorCategory.TRANSIENT,
            retryable = true,
            message = "The selected deployment is temporarily unavailable",
            requestId = attempt.requestId,
        ),
    )
