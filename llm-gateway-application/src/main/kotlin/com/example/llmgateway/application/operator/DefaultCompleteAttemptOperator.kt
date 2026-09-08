package com.example.llmgateway.application.operator

import com.example.llmgateway.application.policy.AttemptPolicy
import com.example.llmgateway.application.policy.FailureClassifier
import com.example.llmgateway.application.policy.FailurePolicy
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
import com.example.llmgateway.domain.model.FailureClass
import com.example.llmgateway.domain.model.GatewayException
import com.example.llmgateway.domain.model.ProviderResponse
import com.example.llmgateway.domain.model.ProviderException
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
    private val outputGuardrailOperator: OutputGuardrailOperator = NoOpOutputGuardrailOperator,
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
                AttemptOutcome.Failure(
                    failureClass = failureClass,
                    usage = response.usage,
                    cost = costCalculationOperator.calculate(deployment, response.usage, Instant.now()),
                    providerRequestId = response.providerRequestId,
                ),
                attempt,
            )
            throw error
        }

        circuitBreaker.onSuccess(deployment)
        val outcome = AttemptOutcome.Success(
            response.usage,
            costCalculationOperator.calculate(deployment, response.usage, Instant.now()),
            response.providerRequestId,
        )
        record(outcome, attempt)
        return response
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
        val providerRequestId = (error as? ProviderException)?.providerRequestId
        record(AttemptOutcome.Failure(failure, providerRequestId = providerRequestId), context)
        return AttemptFailureException.from(failure, error, providerRequestId = providerRequestId)
    }

    private fun record(outcome: AttemptOutcome, context: AttemptContext) {
        runCatching { attemptAccounting.record(context, outcome) }
        runCatching { attemptObserver.onStop(context, outcome) }
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
