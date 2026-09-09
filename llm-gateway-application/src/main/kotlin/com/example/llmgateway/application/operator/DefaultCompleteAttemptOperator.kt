package com.example.llmgateway.application.operator

import com.example.llmgateway.application.policy.AttemptPolicy
import com.example.llmgateway.application.policy.FailureClassifier
import com.example.llmgateway.application.policy.FailurePolicy
import com.example.llmgateway.application.policy.GatewayDeadlineExceededException
import com.example.llmgateway.application.port.out.AttemptJournalPort
import com.example.llmgateway.application.port.out.AttemptObserverPort
import com.example.llmgateway.application.port.out.CircuitBreakerPort
import com.example.llmgateway.application.port.out.DeploymentAvailabilityPort
import com.example.llmgateway.application.port.out.ProviderInvokerPort
import com.example.llmgateway.core.primitive.AttemptId
import com.example.llmgateway.domain.accounting.PricingSnapshot
import com.example.llmgateway.domain.error.FailureClass
import com.example.llmgateway.domain.error.GatewayException
import com.example.llmgateway.domain.error.ProviderException
import com.example.llmgateway.domain.execution.AttemptCancelled
import com.example.llmgateway.domain.execution.AttemptContext
import com.example.llmgateway.domain.execution.AttemptFailure
import com.example.llmgateway.domain.execution.AttemptKind
import com.example.llmgateway.domain.execution.AttemptOutcome
import com.example.llmgateway.domain.execution.AttemptSuccess
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.inference.chat.ProviderResponse
import com.example.llmgateway.domain.observation.AttemptObservationHandle
import com.example.llmgateway.domain.observation.NoOpAttemptObservationHandle
import com.example.llmgateway.domain.observation.withObservationScope
import com.example.llmgateway.domain.routing.CircuitPermit
import com.example.llmgateway.domain.routing.Deployment
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeoutException

class DefaultCompleteAttemptOperator(
    private val providerInvoker: ProviderInvokerPort,
    private val failureClassifier: FailureClassifier,
    private val attemptObserver: AttemptObserverPort,
    private val deadlineOperator: VirtualThreadDeadlineOperator,
    private val circuitBreaker: CircuitBreakerPort,
    private val failurePolicy: FailurePolicy,
    private val deploymentAvailability: DeploymentAvailabilityPort,
    private val attemptAccounting: AttemptJournalPort,
    private val attemptPolicy: AttemptPolicy = AttemptPolicy(failurePolicy),
    private val costCalculationOperator: CostCalculationOperator = DefaultCostCalculationOperator(),
    private val outputGuardrailOperator: OutputGuardrailOperator = NoOpOutputGuardrailOperator,
) : CompleteAttemptOperator {

    override fun execute(
        request: CanonicalChatRequest,
        context: RequestContext,
        deployment: Deployment,
        attemptSequence: Int,
        attemptKind: AttemptKind,
        pricing: PricingSnapshot?,
    ): ProviderResponse {
        val attempt = attemptContext(context, deployment, attemptSequence, attemptKind).copy(
            budgetAt = context.startedAt,
            requestDeadline = context.deadline,
            requestedOutputTokens = request.options.maxCompletionTokens ?: request.options.maxTokens,
        )
        deploymentAvailability.requireEnabled(deployment.id, context)
        val permit = prepareAttempt(attemptAccounting, circuitBreaker, attempt, pricing)
        val observation = runCatching { attemptObserver.start(attempt) }.getOrDefault(NoOpAttemptObservationHandle)
        return observation.withObservationScope {
            try {
                val response = try {
                    deadlineOperator.execute(attempt.deadline) {
                        providerInvoker.complete(deployment, request, attempt)
                    }
                } catch (error: InterruptedException) {
                    record(AttemptCancelled, attempt, observation)
                    Thread.currentThread().interrupt()
                    throw error
                } catch (error: TimeoutException) {
                    throw providerFailure(error.asGatewayDeadlineFailure(context), attempt, deployment, permit, observation)
                } catch (error: Exception) {
                    throw providerFailure(error, attempt, deployment, permit, observation)
                }

                circuitBreaker.onSuccess(deployment, permit)
                try {
                    outputGuardrailOperator.inspectComplete(
                        output = response.text,
                        context = context,
                    )
                } catch (error: GatewayException) {
                    val failureClass = if (error.error.code == "GUARDRAIL_UNAVAILABLE") {
                        FailureClass.TRANSIENT
                    } else {
                        FailureClass.CONTENT_POLICY
                    }
                    record(
                        AttemptFailure(
                            failureClass = failureClass,
                            usage = response.usage,
                            cost = costCalculationOperator.calculate(pricing, response.usage),
                            providerRequestId = response.providerRequestId,
                        ),
                        attempt,
                        observation,
                    )
                    throw error
                }

                val outcome = AttemptSuccess(
                    response.usage,
                    costCalculationOperator.calculate(pricing, response.usage),
                    response.providerRequestId,
                )
                record(outcome, attempt, observation)
                response
            } finally {
                runCatching { observation.stop(AttemptFailure(FailureClass.UNKNOWN)) }
            }
        }
    }

    private fun providerFailure(
        error: Exception,
        context: AttemptContext,
        deployment: Deployment,
        permit: CircuitPermit,
        observation: AttemptObservationHandle,
    ): AttemptFailureException {
        val failure = failureClassifier.classify(error)
        if (failurePolicy.circuitBreakerEligible(failure)) {
            circuitBreaker.onFailure(deployment, permit, failure)
        } else if (error is ProviderException) {
            circuitBreaker.onIgnored(deployment, permit)
        }
        val providerRequestId = (error as? ProviderException)?.providerRequestId
        record(AttemptFailure(failure, providerRequestId = providerRequestId), context, observation)
        return AttemptFailureException.from(failure, error, providerRequestId = providerRequestId)
    }

    private fun record(outcome: AttemptOutcome, context: AttemptContext, observation: AttemptObservationHandle) {
        try {
            recordAccounting(context.requestId) { attemptAccounting.record(context, outcome) }
        } finally {
            runCatching { observation.stop(outcome) }
        }
    }

    private fun attemptContext(
        context: RequestContext,
        deployment: Deployment,
        attemptSequence: Int,
        attemptKind: AttemptKind,
    ): AttemptContext {
        val startedAt = Instant.now()
        return AttemptContext(
            requestId = context.requestId,
            executionId = context.executionId,
            kind = attemptKind,
            attemptId = AttemptId("att_${UUID.randomUUID()}"),
            sequence = attemptSequence,
            deployment = deployment,
            caller = context.caller,
            tenant = context.tenant,
            traceId = context.traceId,
            startedAt = startedAt,
            deadline = attemptPolicy.deadlineFor(context.deadline, startedAt),
        )
    }

    private fun TimeoutException.asGatewayDeadlineFailure(context: RequestContext): Exception =
        if (Instant.now().isBefore(context.deadline)) this else GatewayDeadlineExceededException(this)
}
