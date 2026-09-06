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
import com.example.llmgateway.domain.model.ProviderResponse
import com.example.llmgateway.domain.model.RequestContext
import com.example.llmgateway.domain.model.costOf
import java.time.Instant
import java.util.UUID

class DefaultCompleteAttemptOperator(
    private val providerInvoker: ProviderInvokerPort,
    private val failureClassifier: FailureClassifier,
    private val attemptObserver: AttemptObserverPort,
    private val deadlineOperator: VirtualThreadDeadlineOperator,
    private val circuitBreaker: CircuitBreakerPort,
    private val failurePolicy: FailurePolicy,
) : CompleteAttemptOperator {

    override fun execute(
        request: CanonicalChatRequest,
        context: RequestContext,
        deployment: Deployment,
        attemptSequence: Int,
    ): ProviderResponse {
        val attempt = attemptContext(context, deployment, attemptSequence)
        attemptObserver.onStart(attempt)
        return try {
            val response = deadlineOperator.execute(context.deadline) {
                providerInvoker.complete(deployment, request)
            }
            circuitBreaker.onSuccess(deployment)
            attemptObserver.onStop(attempt, AttemptOutcome.Success(response.usage, deployment.costOf(response.usage)))
            response
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
            throw AttemptFailureException(failure, cause = error)
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
        startedAt = Instant.now(),
    )
}
