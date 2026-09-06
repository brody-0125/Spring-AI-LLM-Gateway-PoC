package com.example.llmgateway.application.operator

import com.example.llmgateway.application.policy.FailureClassifier
import com.example.llmgateway.application.policy.FailurePolicy
import com.example.llmgateway.application.port.out.AttemptObserverPort
import com.example.llmgateway.application.port.out.CircuitBreakerPort
import com.example.llmgateway.application.port.out.ProviderInvokerPort
import com.example.llmgateway.core.primitive.AttemptId
import com.example.llmgateway.domain.model.AttemptContext
import com.example.llmgateway.domain.model.AttemptOutcome
import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.Deployment
import com.example.llmgateway.domain.model.GatewayEvent
import com.example.llmgateway.domain.model.RequestContext
import com.example.llmgateway.domain.model.Usage
import com.example.llmgateway.domain.model.costOf
import java.time.Instant
import java.util.UUID

class DefaultStreamAttemptOperator(
    private val providerInvoker: ProviderInvokerPort,
    private val failureClassifier: FailureClassifier,
    private val attemptObserver: AttemptObserverPort,
    private val deadlineOperator: VirtualThreadDeadlineOperator,
    private val circuitBreaker: CircuitBreakerPort,
    private val failurePolicy: FailurePolicy,
) : StreamAttemptOperator {

    override fun execute(
        request: CanonicalChatRequest,
        context: RequestContext,
        deployment: Deployment,
        attemptSequence: Int,
        responseId: String,
    ): Sequence<GatewayEvent> = sequence {
        val attempt = attemptContext(context, deployment, attemptSequence)
        attemptObserver.onStart(attempt)
        var emitted = false
        var usage = Usage()
        var finishReason = "stop"

        try {
            val providerChunks = deadlineOperator.execute(context.deadline) {
                providerInvoker.stream(deployment, request)
            }
            val iterator = providerChunks.iterator()
            while (deadlineOperator.execute(context.deadline) { iterator.hasNext() }) {
                val chunk = deadlineOperator.execute(context.deadline) { iterator.next() }
                emitted = true
                chunk.usage?.let { usage = it }
                chunk.finishReason?.let { finishReason = it }
                yield(
                    GatewayEvent.Delta(
                        id = responseId,
                        text = chunk.text,
                        model = request.modelGroup.value,
                        createdAtEpochSeconds = attempt.startedAt.epochSecond,
                    ),
                )
            }
            circuitBreaker.onSuccess(deployment)
            attemptObserver.onStop(attempt, AttemptOutcome.Success(usage, deployment.costOf(usage)))
            yield(
                GatewayEvent.Complete(
                    id = responseId,
                    usage = usage,
                    model = request.modelGroup.value,
                    createdAtEpochSeconds = attempt.startedAt.epochSecond,
                    finishReason = finishReason,
                ),
            )
        } catch (error: InterruptedException) {
            attemptObserver.onStop(attempt, AttemptOutcome.Cancelled)
            Thread.currentThread().interrupt()
            throw error
        } catch (error: Exception) {
            val failure = failureClassifier.classify(error)
            if (failurePolicy.circuitBreakerEligible(failure)) {
                circuitBreaker.onFailure(deployment, failure)
            }
            attemptObserver.onStop(attempt, AttemptOutcome.Failure(failure))
            throw AttemptFailureException(failure, emitted, error)
        }
    }

    private fun attemptContext(
        context: RequestContext,
        deployment: Deployment,
        attemptSequence: Int,
    ) = AttemptContext(
        requestId = context.requestId,
        attemptId = AttemptId("att_${UUID.randomUUID()}"),
        sequence = attemptSequence,
        deployment = deployment,
        caller = context.caller,
        tenant = context.tenant,
        traceId = context.traceId,
        streaming = true,
        startedAt = Instant.now(),
    )
}
