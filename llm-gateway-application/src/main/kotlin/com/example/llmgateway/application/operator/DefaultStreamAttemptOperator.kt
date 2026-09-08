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
import com.example.llmgateway.domain.model.GatewayEvent
import com.example.llmgateway.domain.model.GatewayException
import com.example.llmgateway.domain.model.ProviderException
import com.example.llmgateway.domain.model.RequestContext
import com.example.llmgateway.domain.model.Usage
import com.example.llmgateway.domain.model.costOf
import java.time.Instant
import java.util.concurrent.TimeoutException
import java.util.UUID

class DefaultStreamAttemptOperator(
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
        var usage = Usage(available = false)
        var finishReason = "stop"
        var firstTokenRecorded = false
        var providerActive = true
        var providerRequestId: String? = null
        val outputGuardrail = outputGuardrailOperator.openStream(context)

        try {
            val providerChunks = deadlineOperator.execute(attempt.deadline) {
                providerInvoker.stream(deployment, request, attempt)
            }
            val iterator = providerChunks.iterator()
            while (deadlineOperator.execute(attempt.deadline) { iterator.hasNext() }) {
                val chunk = deadlineOperator.execute(attempt.deadline) { iterator.next() }
                chunk.providerRequestId?.let { providerRequestId = it }
                chunk.usage?.let { usage = usage.mergeCumulative(it) }
                chunk.finishReason?.let { finishReason = it }
                outputGuardrail.inspect(chunk.text)
                emitted = true
                if (chunk.text.isNotEmpty() && !firstTokenRecorded) {
                    firstTokenRecorded = true
                    attemptObserver.onFirstToken(attempt)
                }
                try {
                    yield(
                        GatewayEvent.Delta(
                            id = responseId,
                            text = chunk.text,
                            model = request.modelGroup.value,
                            createdAtEpochSeconds = attempt.startedAt.epochSecond,
                        ),
                    )
                } catch (error: Throwable) {
                    providerActive = false
                    throw error
                }
            }
            providerActive = false
            circuitBreaker.onSuccess(deployment)
            val outcome = AttemptOutcome.Success(
                usage,
                costCalculationOperator.calculate(deployment, usage, Instant.now()),
                providerRequestId,
            )
            record(outcome, attempt)
            yield(
                GatewayEvent.Complete(
                    id = responseId,
                    usage = usage,
                    model = request.modelGroup.value,
                    createdAtEpochSeconds = attempt.startedAt.epochSecond,
                    finishReason = finishReason,
                ),
            )
        } catch (error: GatewayException) {
            val failureClass = if (error.error.code == "GUARDRAIL_UNAVAILABLE") {
                com.example.llmgateway.domain.model.FailureClass.UNKNOWN
            } else {
                com.example.llmgateway.domain.model.FailureClass.CONTENT_POLICY
            }
            record(
                AttemptOutcome.Failure(
                    failureClass = failureClass,
                    usage = usage,
                    cost = costCalculationOperator.calculate(deployment, usage, Instant.now()),
                    providerRequestId = providerRequestId,
                ),
                attempt,
            )
            providerActive = false
            throw error
        } catch (error: InterruptedException) {
            record(
                AttemptOutcome.CancelledWithUsage(
                    usage = usage,
                    cost = costCalculationOperator.calculate(deployment, usage, Instant.now()),
                    providerRequestId = providerRequestId,
                ),
                attempt,
            )
            Thread.currentThread().interrupt()
            throw error
        } catch (error: TimeoutException) {
            if (providerActive.not()) throw error
            val failure = failureClassifier.classify(error.asGatewayDeadlineFailure(context))
            if (failurePolicy.circuitBreakerEligible(failure)) {
                circuitBreaker.onFailure(deployment, failure)
            }
            val cost = costCalculationOperator.calculate(deployment, usage, Instant.now())
            val requestId = providerRequestId
            record(AttemptOutcome.Failure(failure, usage, cost, requestId), attempt)
            throw AttemptFailureException.from(failure, error, emitted, requestId)
        } catch (error: Exception) {
            if (providerActive.not()) throw error
            val failure = failureClassifier.classify(error)
            if (failurePolicy.circuitBreakerEligible(failure)) {
                circuitBreaker.onFailure(deployment, failure)
            }
            val cost = costCalculationOperator.calculate(deployment, usage, Instant.now())
            val requestId = (error as? ProviderException)?.providerRequestId ?: providerRequestId
            record(AttemptOutcome.Failure(failure, usage, cost, requestId), attempt)
            throw AttemptFailureException.from(failure, error, emitted, requestId)
        }
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
            streaming = true,
            startedAt = startedAt,
            deadline = attemptPolicy.deadlineFor(context.deadline, startedAt),
        )
    }

    private fun TimeoutException.asGatewayDeadlineFailure(context: RequestContext): Exception =
        if (Instant.now().isBefore(context.deadline)) this else GatewayDeadlineExceededException(this)

}
