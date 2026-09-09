package com.example.llmgateway.application.service

import com.example.llmgateway.application.port.out.CircuitBreakerPort
import com.example.llmgateway.application.port.out.RoutePlannerPort
import com.example.llmgateway.application.port.out.RoutingSnapshotPort
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.domain.error.ErrorCategory
import com.example.llmgateway.domain.error.GatewayError
import com.example.llmgateway.domain.error.GatewayException
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.routing.Deployment
import com.example.llmgateway.domain.routing.RoutingPlan
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.math.ln

class WeightedRendezvousRoutePlanner(
    private val deploymentRegistry: RoutingSnapshotPort,
    private val circuitBreaker: CircuitBreakerPort,
) : RoutePlannerPort {

    override fun plan(request: CanonicalChatRequest, context: RequestContext): RoutingPlan =
        plan(request, context, emptySet())

    override fun plan(
        request: CanonicalChatRequest,
        context: RequestContext,
        excludedDeploymentIds: Set<DeploymentId>,
    ): RoutingPlan {
        val snapshot = deploymentRegistry.current()
        val eligible = snapshot.deployments
            .filter { it.enabled && it.modelGroup == request.modelGroup }
            .filter { !request.stream || it.supportsStreaming }
            .filter { it.weight > 0 }
            .filterNot { it.id in excludedDeploymentIds }
            .filter(circuitBreaker::inspect)

        if (eligible.isEmpty()) {
            throw GatewayException(
                GatewayError(
                    type = "no_available_deployment",
                    code = "LLM_UNAVAILABLE",
                    category = ErrorCategory.TRANSIENT,
                    retryable = true,
                    message = "No enabled deployment is available for model group '${request.modelGroup.value}'",
                    requestId = context.requestId,
                ),
            )
        }

        val primaryPriority = eligible.minOf { it.priority }
        val primaryTier = eligible.filter { it.priority == primaryPriority }
        val primary = selectPrimary(context.executionId.value, request.modelGroup, primaryTier)
        val alternates = eligible
            .filterNot { it.id == primary.id }
            .sortedWith(compareBy<Deployment>({ it.priority }, { it.id.value }))
        return RoutingPlan(
            primary = primary,
            alternates = alternates,
            snapshotVersion = snapshot.version,
            pricing = snapshot.pricing,
        )
    }

    private fun selectPrimary(
        executionId: String,
        modelGroup: ModelGroup,
        eligible: List<Deployment>,
    ): Deployment = eligible.minWith(
        compareBy<Deployment>({ weightedScore(executionId, modelGroup, it) }, { it.id.value }),
    )

    private fun weightedScore(executionId: String, modelGroup: ModelGroup, deployment: Deployment): Double {
        val input = "$executionId|${modelGroup.value}|${deployment.id.value}"
            .toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        val unsigned = BigInteger(1, digest.copyOfRange(0, 8))
        val unit = (unsigned.toDouble() + 1.0) / (BigInteger.ONE.shiftLeft(64).toDouble() + 1.0)
        return -ln(unit) / deployment.weight.toDouble()
    }
}
