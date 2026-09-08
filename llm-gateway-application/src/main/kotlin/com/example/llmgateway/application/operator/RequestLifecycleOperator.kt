package com.example.llmgateway.application.operator

import com.example.llmgateway.application.port.out.NoOpRequestAccountingPort
import com.example.llmgateway.application.port.out.NoOpRequestObserverPort
import com.example.llmgateway.application.port.out.RequestAccountingPort
import com.example.llmgateway.application.port.out.RequestObserverPort
import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.GatewayException
import com.example.llmgateway.domain.model.RequestContext
import com.example.llmgateway.domain.model.RequestOutcome
import com.example.llmgateway.domain.model.RequestOutcomeStatus

class RequestLifecycleOperator(
    private val observer: RequestObserverPort = NoOpRequestObserverPort,
    private val accounting: RequestAccountingPort = NoOpRequestAccountingPort,
) {
    fun <T> execute(
        request: CanonicalChatRequest,
        context: RequestContext,
        operation: () -> T,
    ): T {
        start(request, context)
        var outcome = RequestOutcome(RequestOutcomeStatus.CANCELLED)
        try {
            val result = operation()
            outcome = RequestOutcome(RequestOutcomeStatus.SUCCESS)
            return result
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: GatewayException) {
            outcome = error.outcome()
            throw error
        } catch (error: Exception) {
            outcome = RequestOutcome(
                status = RequestOutcomeStatus.FAILURE,
                errorType = "gateway_error",
                errorCode = "GATEWAY_INTERNAL_ERROR",
            )
            throw error
        } finally {
            finish(request, context, outcome)
        }
    }

    fun <T> stream(
        request: CanonicalChatRequest,
        context: RequestContext,
        operation: () -> Sequence<T>,
    ): Sequence<T> = sequence {
        start(request, context)
        var outcome = RequestOutcome(RequestOutcomeStatus.CANCELLED)
        try {
            yieldAll(operation())
            outcome = RequestOutcome(RequestOutcomeStatus.SUCCESS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: GatewayException) {
            outcome = error.outcome()
            throw error
        } catch (error: Exception) {
            outcome = RequestOutcome(
                status = RequestOutcomeStatus.FAILURE,
                errorType = "gateway_error",
                errorCode = "GATEWAY_INTERNAL_ERROR",
            )
            throw error
        } finally {
            finish(request, context, outcome)
        }
    }

    private fun start(request: CanonicalChatRequest, context: RequestContext) {
        runCatching { observer.onStart(context, request) }
    }

    private fun finish(
        request: CanonicalChatRequest,
        context: RequestContext,
        outcome: RequestOutcome,
    ) {
        runCatching { accounting.record(context, request, outcome) }
        runCatching { observer.onStop(context, request, outcome) }
    }

    private fun GatewayException.outcome() = RequestOutcome(
        status = RequestOutcomeStatus.FAILURE,
        errorType = error.type,
        errorCode = error.code,
    )
}
