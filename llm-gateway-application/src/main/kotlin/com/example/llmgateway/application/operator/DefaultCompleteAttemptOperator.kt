package com.example.llmgateway.application.operator

import com.example.llmgateway.application.policy.FailureClassifier
import com.example.llmgateway.application.policy.FailurePolicy
import com.example.llmgateway.application.policy.AttemptPolicy
import com.example.llmgateway.application.policy.GatewayDeadlineExceededException
import com.example.llmgateway.application.port.out.AttemptAccountingPort
import com.example.llmgateway.application.port.out.AttemptObserverPort
import com.example.llmgateway.application.port.out.CircuitBreakerPort
import com.example.llmgateway.application.port.out.NoOpAttemptAccountingPort
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
import java.util.concurrent.TimeoutException
import java.util.UUID

class DefaultCompleteAttemptOperator(
    private val providerInvoker: ProviderInvokerPort,
    private val failureClassifier: FailureClassifier,
    private val attemptObserver: AttemptObserverPort,
    private val deadlineOperator: VirtualThreadDeadlineOperator,
    private val circuitBreaker: CircuitBreakerPort,
    private val failurePolicy: FailurePolicy,
    private val attemptPolicy: AttemptPolicy = AttemptPolicy(failurePolicy),
    private val costCalculationOperator: CostCalculationOperator = LegacyCostCalculationOperator,
    private val attemptAccounting: AttemptAccountingPort = NoOpAttemptAccountingPort,
) : CompleteAttemptOperator {

    override fun execute(
        request: CanonicalChatRequest,
        context: RequestContext,
        deployment: Deployment,
        attemptSequence: Int,
    ): ProviderResponse {
        val attempt = attemptContext(context, deployment, attemptSequence)
        attemptObserver.onStart(attempt)
        val response = try {
            deadlineOperator.execute(attempt.deadline) {
                providerInvoker.complete(deployment, request, attempt)
            }
        } catch (error: InterruptedException) {
            record(AttemptOutcome.Cancelled, attempt)
            Thread.currentThread().interrupt()
            throw error
        } catch (error: TimeoutException) {
            throw providerFailure(error.asGatewayDeadlineFailure(context), attempt, deployment)
        } catch (error: Exception) {
            throw providerFailure(error, attempt, deployment)
        }

        return try {
            circuitBreaker.onSuccess(deployment)
            val outcome = AttemptOutcome.Success(
                response.usage,
                costCalculationOperator.calculate(deployment, response.usage, Instant.now()),
            )
            record(outcome, attempt)
            response
        } catch (error: AttemptAccountingException) {
            throw error
        }
    }

    private fun providerFailure(
        error: Exception,
        context: AttemptContext,
        deployment: Deployment,
    ): AttemptFailureException {
        val failure = failureClassifier.classify(error)
        if (failurePolicy.circuitBreakerEligible(failure)) {
            circuitBreaker.onFailure(deployment, failure)
        }
        record(AttemptOutcome.Failure(failure), context)
        return AttemptFailureException.from(failure, error)
    }

    private fun record(outcome: AttemptOutcome, context: AttemptContext) {
        try {
            attemptAccounting.record(context, outcome)
        } catch (error: Exception) {
            throw AttemptAccountingException(error)
        }
        attemptObserver.onStop(context, outcome)
    }

    private fun attemptContext(
        context: RequestContext,
        deployment: Deployment,
        attemptSequence: Int,
    ): AttemptContext {
        val startedAt = Instant.now()
        return AttemptContext(
            requestId = context.requestId,
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
