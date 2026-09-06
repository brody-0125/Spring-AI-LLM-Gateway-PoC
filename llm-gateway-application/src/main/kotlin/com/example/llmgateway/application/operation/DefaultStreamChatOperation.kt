package com.example.llmgateway.application.operation

import com.example.llmgateway.application.operator.AttemptFailureException
import com.example.llmgateway.application.operator.StreamAttemptOperator
import com.example.llmgateway.application.policy.FallbackPolicy
import com.example.llmgateway.application.policy.GatewayErrorFactory
import com.example.llmgateway.application.port.out.RoutePlannerPort
import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.GatewayEvent
import com.example.llmgateway.domain.model.RequestContext
import com.example.llmgateway.domain.model.RoutingPlan
import java.util.UUID

class DefaultStreamChatOperation(
    private val routePlanner: RoutePlannerPort,
    private val attemptOperator: StreamAttemptOperator,
    private val fallbackPolicy: FallbackPolicy,
    private val errorFactory: GatewayErrorFactory,
) : StreamChatOperation {

    override fun execute(request: CanonicalChatRequest, context: RequestContext): Sequence<GatewayEvent> = sequence {
        val plan = routePlanner.plan(request, context)
        yieldAll(executeAttempt(plan, 0, request, context, "chatcmpl_${UUID.randomUUID()}"))
    }

    private fun executeAttempt(
        plan: RoutingPlan,
        index: Int,
        request: CanonicalChatRequest,
        context: RequestContext,
        responseId: String,
    ): Sequence<GatewayEvent> = sequence {
        val deployment = plan.candidates.getOrNull(index)
            ?: throw errorFactory.noDeployment(context)

        try {
            attemptOperator.execute(request, context, deployment, index + 1, responseId)
                .forEach { event -> yield(event) }
        } catch (error: AttemptFailureException) {
            if (error.emitted.not() && fallbackPolicy.canFallback(plan, index, error.failureClass)) {
                executeAttempt(plan, index + 1, request, context, responseId)
                    .forEach { event -> yield(event) }
            } else {
                throw errorFactory.from(context, error.failureClass, error.cause ?: error)
            }
        }
    }
}
