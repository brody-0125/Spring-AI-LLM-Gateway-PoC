package com.example.llmgateway.application.operation

import com.example.llmgateway.application.operator.AttemptFailureException
import com.example.llmgateway.application.operator.StreamAttemptOperator
import com.example.llmgateway.application.policy.AttemptPolicy
import com.example.llmgateway.application.policy.GatewayErrorFactory
import com.example.llmgateway.application.port.out.RoutePlannerPort
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.domain.error.FailureClass
import com.example.llmgateway.domain.error.GatewayException
import com.example.llmgateway.domain.execution.AttemptKind
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.inference.chat.GatewayEvent
import com.example.llmgateway.domain.routing.RoutingPlan
import com.example.llmgateway.domain.stream.CloseableStream
import com.example.llmgateway.domain.stream.ManagedStream
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeoutException

class DefaultStreamChatOperation(
    private val routePlanner: RoutePlannerPort,
    private val attemptOperator: StreamAttemptOperator,
    private val attemptPolicy: AttemptPolicy,
    private val errorFactory: GatewayErrorFactory,
) : StreamChatOperation {

    override fun execute(request: CanonicalChatRequest, context: RequestContext): CloseableStream<GatewayEvent> = ManagedStream { scope -> sequence {
        val budget = attemptPolicy.newBudget()
        var attemptKind = AttemptKind.INITIAL
        val responseId = "chatcmpl_${UUID.randomUUID()}"
        var excluded = emptySet<DeploymentId>()
        var lastFailure: AttemptFailureException? = null
        val plan = plan(request, context, excluded)

        while (budget.attempts < attemptPolicy.maxTotalAttempts) {
            if (remaining(context).isZero || remaining(context).isNegative) {
                throw lastFailure?.let { terminalError(context, it) }
                    ?: errorFactory.gatewayTimeout(context, TimeoutException("Gateway deadline exceeded"))
            }
            val deployment = plan.candidates.firstOrNull { it.id !in excluded }
                ?: break
            var retryIndex = 0
            var fallback = false

            while (budget.startAttempt()) {
                try {
                    scope.own(attemptOperator.execute(request, context, deployment, budget.attempts, responseId, attemptKind, plan.pricing[deployment.id]))
                        .use { stream -> stream.forEach { event -> yield(event) } }
                    return@sequence
                } catch (error: AttemptFailureException) {
                    lastFailure = error
                    val delay = if (
                        attemptPolicy.canRetrySameDeployment(
                            failureClass = error.failureClass,
                            emitted = error.emitted,
                            requestDisposition = error.requestDisposition,
                            retryIndex = retryIndex,
                            budget = budget,
                        )
                    ) {
                        attemptPolicy.delay(error.retryAfter, retryIndex, remaining(context))
                    } else {
                        null
                    }
                    if (delay != null) {
                        attemptPolicy.await(delay)
                        retryIndex += 1
                        attemptKind = AttemptKind.RETRY
                        continue
                    }

                    if (remaining(context).isZero || remaining(context).isNegative) {
                        throw terminalError(context, error)
                    }

                    val nextExcluded = excluded + deployment.id
                    if (
                        !attemptPolicy.canFallback(error.failureClass, error.emitted, budget) ||
                        plan.candidates.none { it.id !in nextExcluded } ||
                        !budget.startFallback()
                    ) {
                        throw terminalError(context, error)
                    }
                    excluded = excluded + deployment.id
                    attemptKind = AttemptKind.FALLBACK
                    fallback = true
                    break
                }
            }

            if (!fallback) break
        }

        throw lastFailure?.let { terminalError(context, it) }
            ?: errorFactory.noDeployment(context)
    } }

    private fun plan(
        request: CanonicalChatRequest,
        context: RequestContext,
        excluded: Set<DeploymentId>,
    ): RoutingPlan = try {
        routePlanner.plan(request, context, excluded)
    } catch (error: GatewayException) {
        throw error
    } catch (error: Exception) {
        throw errorFactory.routingUnavailable(context, error)
    }

    private fun remaining(context: RequestContext): Duration =
        Duration.between(Instant.now(), context.deadline)

    private fun terminalError(context: RequestContext, failure: AttemptFailureException): GatewayException =
        errorFactory.from(
            context = context,
            failure = failure.failureClass,
            cause = failure.cause ?: failure,
            retryAfter = failure.retryAfter,
        )
}
