package com.example.llmgateway.application.operation

import com.example.llmgateway.application.operator.AttemptFailureException
import com.example.llmgateway.application.operator.CompleteAttemptOperator
import com.example.llmgateway.application.policy.FallbackPolicy
import com.example.llmgateway.application.policy.GatewayErrorFactory
import com.example.llmgateway.application.port.out.RoutePlannerPort
import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.GatewayResponse
import com.example.llmgateway.domain.model.RequestContext
import com.example.llmgateway.domain.model.RoutingPlan
import java.util.UUID
import java.time.Instant

class DefaultCompleteChatOperation(
    private val routePlanner: RoutePlannerPort,
    private val attemptOperator: CompleteAttemptOperator,
    private val fallbackPolicy: FallbackPolicy,
    private val errorFactory: GatewayErrorFactory,
) : CompleteChatOperation {

    override fun execute(request: CanonicalChatRequest, context: RequestContext): GatewayResponse =
        executeAttempt(routePlanner.plan(request, context), 0, request, context)

    private fun executeAttempt(
        plan: RoutingPlan,
        index: Int,
        request: CanonicalChatRequest,
        context: RequestContext,
    ): GatewayResponse {
        val deployment = plan.candidates.getOrNull(index)
            ?: throw errorFactory.noDeployment(context)

        return try {
            attemptOperator.execute(request, context, deployment, index + 1)
                .toGatewayResponse(request)
        } catch (error: AttemptFailureException) {
            if (fallbackPolicy.canFallback(plan, index, error.failureClass)) {
                executeAttempt(plan, index + 1, request, context)
            } else {
                throw errorFactory.from(context, error.failureClass, error.cause ?: error)
            }
        }
    }
}

private fun com.example.llmgateway.domain.model.ProviderResponse.toGatewayResponse(
    request: CanonicalChatRequest,
) = GatewayResponse(
    id = "chatcmpl_${UUID.randomUUID().toString().replace("-", "")}",
    model = request.modelGroup.value,
    text = text,
    usage = usage,
    createdAtEpochSeconds = Instant.now().epochSecond,
)
