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
import com.example.llmgateway.domain.accounting.Usage
import com.example.llmgateway.domain.error.FailureClass
import com.example.llmgateway.domain.error.GatewayException
import com.example.llmgateway.domain.error.ProviderException
import com.example.llmgateway.domain.execution.AttemptCancelledWithUsage
import com.example.llmgateway.domain.execution.AttemptContext
import com.example.llmgateway.domain.execution.AttemptFailure
import com.example.llmgateway.domain.execution.AttemptKind
import com.example.llmgateway.domain.execution.AttemptOutcome
import com.example.llmgateway.domain.execution.AttemptSuccess
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.inference.chat.GatewayCompleteEvent
import com.example.llmgateway.domain.inference.chat.GatewayDeltaEvent
import com.example.llmgateway.domain.inference.chat.GatewayEvent
import com.example.llmgateway.domain.observation.AttemptObservationHandle
import com.example.llmgateway.domain.observation.NoOpAttemptObservationHandle
import com.example.llmgateway.domain.observation.withObservationScope
import com.example.llmgateway.domain.routing.Deployment
import com.example.llmgateway.domain.stream.CloseableStream
import com.example.llmgateway.domain.stream.ManagedStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DefaultStreamAttemptOperator(
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
) : StreamAttemptOperator {

    override fun execute(
        request: CanonicalChatRequest,
        context: RequestContext,
        deployment: Deployment,
        attemptSequence: Int,
        responseId: String,
        attemptKind: AttemptKind,
        pricing: PricingSnapshot?,
    ): CloseableStream<GatewayEvent> = ManagedStream { scope -> sequence {
        val attempt = attemptContext(context, deployment, attemptSequence, attemptKind).copy(
            budgetAt = context.startedAt,
            requestDeadline = context.deadline,
            requestedOutputTokens = request.options.maxCompletionTokens ?: request.options.maxTokens,
        )
        val outputGuardrail = outputGuardrailOperator.openStream(context)
        deploymentAvailability.requireEnabled(deployment.id, context)
        val permit = prepareAttempt(attemptAccounting, circuitBreaker, attempt, pricing)
        val observation = runCatching { attemptObserver.start(attempt) }.getOrDefault(NoOpAttemptObservationHandle)
        scope.own(AutoCloseable {
            runCatching { observation.stop(AttemptFailure(FailureClass.UNKNOWN)) }
        })
        fun <R> providerCall(action: () -> R): R = observation.withObservationScope {
            deadlineOperator.execute(attempt.deadline, action)
        }
        var emitted = false
        val usage = AtomicReference(Usage(emptyList()))
        var finishReason = "stop"
        var firstTokenRecorded = false
        var providerActive = true
        val providerRequestId = AtomicReference<String?>()
        val recorded = AtomicBoolean()
        fun recordOnce(outcome: AttemptOutcome, context: AttemptContext) {
            if (recorded.compareAndSet(false, true)) record(outcome, context, observation)
        }
        scope.own(AutoCloseable {
            recordOnce(AttemptCancelledWithUsage(
                usage.get(), costCalculationOperator.calculate(pricing, usage.get()), providerRequestId.get()), attempt)
        })

        try {
            val providerChunks = providerCall {
                scope.ensureOpen()
                scope.own(providerInvoker.stream(deployment, request, attempt))
            }
            val iterator = providerChunks.iterator()
            while (providerCall { iterator.hasNext() }) {
                val chunk = providerCall { iterator.next() }
                chunk.providerRequestId?.let { providerRequestId.set(it) }
                chunk.usage?.let { usage.updateAndGet { current -> current.mergeCumulative(it) } }
                chunk.finishReason?.let { finishReason = it }
                outputGuardrail.inspect(chunk.text)
                emitted = true
                if (chunk.text.isNotEmpty() && !firstTokenRecorded) {
                    firstTokenRecorded = true
                    runCatching { observation.firstToken() }
                }
                try {
                    yield(
                        GatewayDeltaEvent(
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
            scope.ensureOpen()
            providerActive = false
            circuitBreaker.onSuccess(deployment, permit)
            val outcome = AttemptSuccess(
                usage.get(),
                costCalculationOperator.calculate(pricing, usage.get()),
                providerRequestId.get(),
            )
            recordOnce(outcome, attempt)
            yield(
                GatewayCompleteEvent(
                    id = responseId,
                    usage = usage.get(),
                    model = request.modelGroup.value,
                    createdAtEpochSeconds = attempt.startedAt.epochSecond,
                    finishReason = finishReason,
                ),
            )
        } catch (error: GatewayException) {
            val failureClass = if (error.error.code == "GUARDRAIL_UNAVAILABLE") {
                com.example.llmgateway.domain.error.FailureClass.UNKNOWN
            } else {
                com.example.llmgateway.domain.error.FailureClass.CONTENT_POLICY
            }
            recordOnce(
                AttemptFailure(
                    failureClass = failureClass,
                    usage = usage.get(),
                    cost = costCalculationOperator.calculate(pricing, usage.get()),
                    providerRequestId = providerRequestId.get(),
                ),
                attempt,
            )
            providerActive = false
            throw error
        } catch (error: CancellationException) {
            recordOnce(AttemptCancelledWithUsage(
                usage.get(), costCalculationOperator.calculate(pricing, usage.get()), providerRequestId.get()), attempt)
            throw error
        } catch (error: InterruptedException) {
            recordOnce(
                AttemptCancelledWithUsage(
                    usage = usage.get(),
                    cost = costCalculationOperator.calculate(pricing, usage.get()),
                    providerRequestId = providerRequestId.get(),
                ),
                attempt,
            )
            Thread.currentThread().interrupt()
            throw error
        } catch (error: TimeoutException) {
            if (providerActive.not()) throw error
            val failure = failureClassifier.classify(error.asGatewayDeadlineFailure(context))
            if (failurePolicy.circuitBreakerEligible(failure)) {
                circuitBreaker.onFailure(deployment, permit, failure)
            }
            val cost = costCalculationOperator.calculate(pricing, usage.get())
            val requestId = providerRequestId.get()
            recordOnce(AttemptFailure(failure, usage.get(), cost, requestId), attempt)
            throw AttemptFailureException.from(failure, error, emitted, requestId)
        } catch (error: Exception) {
            if (providerActive.not()) throw error
            val failure = failureClassifier.classify(error)
            if (failurePolicy.circuitBreakerEligible(failure)) {
                circuitBreaker.onFailure(deployment, permit, failure)
            } else if (error is ProviderException) {
                circuitBreaker.onIgnored(deployment, permit)
            }
            val cost = costCalculationOperator.calculate(pricing, usage.get())
            val requestId = (error as? ProviderException)?.providerRequestId ?: providerRequestId.get()
            recordOnce(AttemptFailure(failure, usage.get(), cost, requestId), attempt)
            throw AttemptFailureException.from(failure, error, emitted, requestId)
        }
    } }

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
            streaming = true,
            startedAt = startedAt,
            deadline = attemptPolicy.deadlineFor(context.deadline, startedAt),
        )
    }

    private fun TimeoutException.asGatewayDeadlineFailure(context: RequestContext): Exception =
        if (Instant.now().isBefore(context.deadline)) this else GatewayDeadlineExceededException(this)

}
