package com.example.llmgateway.application.operation

import com.example.llmgateway.application.operator.AttemptFailureException
import com.example.llmgateway.application.operator.StreamAttemptOperator
import com.example.llmgateway.application.policy.AttemptPolicy
import com.example.llmgateway.application.policy.GatewayErrorFactory
import com.example.llmgateway.application.port.out.RoutePlannerPort
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.GatewayEvent
import com.example.llmgateway.domain.model.GatewayException
import com.example.llmgateway.domain.model.FailureClass
import com.example.llmgateway.domain.model.RequestContext
import com.example.llmgateway.domain.model.RoutingPlan
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException
import java.util.UUID

class DefaultStreamChatOperation(
    private val routePlanner: RoutePlannerPort,
    private val attemptOperator: StreamAttemptOperator,
    private val attemptPolicy: AttemptPolicy,
    private val errorFactory: GatewayErrorFactory,
) : StreamChatOperation {

    override fun execute(request: CanonicalChatRequest, context: RequestContext): Sequence<GatewayEvent> = sequence {
        val budget = attemptPolicy.newBudget()
        val responseId = "chatcmpl_${UUID.randomUUID()}"
        var excluded = emptySet<DeploymentId>()
        var lastFailure: AttemptFailureException? = null
        var plan = plan(request, context, excluded)

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
                    attemptOperator.execute(request, context, deployment, budget.attempts, responseId)
                        .forEach { event -> yield(event) }
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
                    plan = plan(request, context, excluded)
                    fallback = true
                    break
                }
            }

            if (!fallback) break
        }

        throw lastFailure?.let { terminalError(context, it) }
            ?: errorFactory.noDeployment(context)
    }

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
