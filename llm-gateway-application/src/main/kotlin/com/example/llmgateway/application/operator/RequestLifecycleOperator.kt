package com.example.llmgateway.application.operator

import com.example.llmgateway.application.port.out.NoOpRequestAccountingPort
import com.example.llmgateway.application.port.out.NoOpRequestObserverPort
import com.example.llmgateway.application.port.out.RequestAccountingPort
import com.example.llmgateway.application.port.out.RequestObserverPort
import com.example.llmgateway.domain.error.GatewayException
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.execution.RequestOutcome
import com.example.llmgateway.domain.execution.RequestOutcomeStatus
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.observation.NoOpObservationHandle
import com.example.llmgateway.domain.observation.ObservationHandle
import com.example.llmgateway.domain.observation.ObservedStream
import com.example.llmgateway.domain.observation.RequestObservationContext
import com.example.llmgateway.domain.observation.withObservationScope
import com.example.llmgateway.domain.stream.CloseableStream
import com.example.llmgateway.domain.stream.ManagedStream
import java.util.concurrent.atomic.AtomicReference

class RequestLifecycleOperator(
    private val observer: RequestObserverPort = NoOpRequestObserverPort,
    private val accounting: RequestAccountingPort = NoOpRequestAccountingPort,
) {
    fun <T> execute(
        request: CanonicalChatRequest,
        context: RequestContext,
        operation: () -> T,
    ): T {
        val observation = start(request, context)
        return observation.withObservationScope {
            var outcome = RequestOutcome(RequestOutcomeStatus.CANCELLED)
            var operationFailure: Throwable? = null
            try {
                val result = operation()
                outcome = RequestOutcome(RequestOutcomeStatus.SUCCESS)
                result
            } catch (error: InterruptedException) {
                operationFailure = error
                Thread.currentThread().interrupt()
                throw error
            } catch (error: GatewayException) {
                operationFailure = error
                outcome = error.outcome()
                throw error
            } catch (error: Exception) {
                operationFailure = error
                outcome = RequestOutcome(
                    status = RequestOutcomeStatus.FAILURE,
                    errorType = "gateway_error",
                    errorCode = "GATEWAY_INTERNAL_ERROR",
                )
                throw error
            } finally {
                try {
                    finish(request, context, outcome, observation, operationFailure)
                } catch (error: Exception) {
                    operationFailure?.takeIf { it !== error }?.let(error::addSuppressed)
                    throw error
                }
            }
        }
    }

    fun <T> stream(
        request: CanonicalChatRequest,
        context: RequestContext,
        operation: () -> CloseableStream<T>,
    ): CloseableStream<T> = ManagedStream { scope -> sequence {
        val observation = start(request, context)
        val outcome = AtomicReference(RequestOutcome(RequestOutcomeStatus.CANCELLED))
        val operationFailure = AtomicReference<Throwable?>()
        scope.own(AutoCloseable { finish(request, context, outcome.get(), observation, operationFailure.get()) })
        try {
            yieldAll(scope.own(ObservedStream(observation.withObservationScope(operation), observation)))
            outcome.set(RequestOutcome(RequestOutcomeStatus.SUCCESS))
        } catch (error: InterruptedException) {
            operationFailure.set(error)
            Thread.currentThread().interrupt()
            throw error
        } catch (error: GatewayException) {
            operationFailure.set(error)
            outcome.set(error.outcome())
            throw error
        } catch (error: Exception) {
            operationFailure.set(error)
            if (error !is java.util.concurrent.CancellationException) {
                outcome.set(RequestOutcome(
                    status = RequestOutcomeStatus.FAILURE,
                    errorType = "gateway_error",
                    errorCode = "GATEWAY_INTERNAL_ERROR",
                ))
            }
            throw error
        }
    } }

    private fun start(request: CanonicalChatRequest, context: RequestContext): ObservationHandle<RequestOutcome> =
        runCatching {
            observer.start(RequestObservationContext(context, request.modelGroup, request.stream))
        }.getOrDefault(NoOpObservationHandle)

    private fun finish(
        request: CanonicalChatRequest,
        context: RequestContext,
        outcome: RequestOutcome,
        observation: ObservationHandle<RequestOutcome>,
        operationFailure: Throwable?,
    ) = observation.withObservationScope {
        var observedOutcome = outcome
        try {
            recordAccounting(context.requestId) { accounting.record(context, request, outcome) }
        } catch (error: GatewayException) {
            // Only first-attempt admission failure proves that this execution did not invoke a provider.
            if (operationFailure is GatewayException && operationFailure.error.code == "ADMISSION_UNAVAILABLE" &&
                operationFailure.error.retryable) {
                operationFailure.addSuppressed(error)
                observedOutcome = operationFailure.outcome()
                throw operationFailure
            }
            observedOutcome = error.outcome()
            throw error
        } finally {
            runCatching { observation.stop(observedOutcome) }
        }
    }

    private fun GatewayException.outcome() = RequestOutcome(
        status = RequestOutcomeStatus.FAILURE,
        errorType = error.type,
        errorCode = error.code,
    )
}
