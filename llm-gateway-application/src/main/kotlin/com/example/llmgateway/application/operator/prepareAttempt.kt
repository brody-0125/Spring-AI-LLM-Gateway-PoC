package com.example.llmgateway.application.operator

import com.example.llmgateway.application.port.out.AttemptJournalPort
import com.example.llmgateway.application.port.out.CircuitBreakerPort
import com.example.llmgateway.domain.accounting.PricingSnapshot
import com.example.llmgateway.domain.error.ErrorCategory
import com.example.llmgateway.domain.error.GatewayError
import com.example.llmgateway.domain.error.GatewayException
import com.example.llmgateway.domain.execution.AttemptContext
import com.example.llmgateway.domain.routing.CircuitPermit

internal fun prepareAttempt(
    journal: AttemptJournalPort,
    circuit: CircuitBreakerPort,
    context: AttemptContext,
    pricing: PricingSnapshot?,
): CircuitPermit {
    fun unavailable(cause: Exception): GatewayException {
        if (cause is InterruptedException) Thread.currentThread().interrupt()
        return GatewayException(GatewayError(
            type = "admission_unavailable", code = "ADMISSION_UNAVAILABLE",
            category = ErrorCategory.TRANSIENT, retryable = context.sequence == 1,
            message = "The gateway could not prepare the next model attempt",
            requestId = context.requestId,
        ), cause)
    }
    try {
        journal.prepare(context, pricing)
    } catch (error: GatewayException) {
        throw error
    } catch (error: Exception) {
        throw unavailable(error)
    }
    val permit = try {
        circuit.acquireCircuitPermit(context)
    } catch (error: Exception) {
        try { journal.abandon(context) } catch (cleanup: Exception) {
            if (cleanup !== error) cleanup.addSuppressed(error)
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw unavailable(cleanup)
        }
        if (error is InterruptedException) Thread.currentThread().interrupt()
        throw error
    }
    try {
        journal.dispatch(context, permit)
    } catch (error: Exception) {
        // The caller has not crossed the provider boundary. Do not guess whether PG committed intent.
        try { circuit.onIgnored(context.deployment, permit) } catch (cleanup: Exception) {
            if (cleanup !== error) error.addSuppressed(cleanup)
        }
        throw unavailable(error)
    }
    return permit
}
