package com.example.llmgateway.application.service

import com.example.llmgateway.application.port.out.CircuitBreakerPort
import com.example.llmgateway.application.port.out.DeploymentRegistryPort
import com.example.llmgateway.application.port.out.RoutePlannerPort
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.Deployment
import com.example.llmgateway.domain.model.ErrorCategory
import com.example.llmgateway.domain.model.GatewayError
import com.example.llmgateway.domain.model.GatewayException
import com.example.llmgateway.domain.model.RequestContext
import com.example.llmgateway.domain.model.RoutingPlan
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.math.ln

class WeightedRendezvousRoutePlanner(
    private val deploymentRegistry: DeploymentRegistryPort,
    private val circuitBreaker: CircuitBreakerPort,
) : RoutePlannerPort {

    override fun plan(request: CanonicalChatRequest, context: RequestContext): RoutingPlan =
        plan(request, context, emptySet())

    override fun plan(
        request: CanonicalChatRequest,
        context: RequestContext,
        excludedDeploymentIds: Set<DeploymentId>,
    ): RoutingPlan {
        val snapshot = deploymentRegistry.snapshot()
        val eligible = snapshot.deployments
            .filter { it.enabled && it.modelGroup == request.modelGroup }
            .filter { !request.stream || it.supportsStreaming }
            .filter { it.weight > 0 }
            .filterNot { it.id in excludedDeploymentIds }
            .filter(circuitBreaker::allow)

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
        val primary = selectPrimary(context.requestId.value, request.modelGroup, primaryTier)
        val alternates = eligible
            .filterNot { it.id == primary.id }
            .sortedWith(compareBy<Deployment>({ it.priority }, { it.id.value }))
        return RoutingPlan(
            primary = primary,
            alternates = alternates,
            snapshotVersion = snapshot.version,
        )
    }

    private fun selectPrimary(
        requestId: String,
        modelGroup: ModelGroup,
        eligible: List<Deployment>,
    ): Deployment = eligible.minWith(
        compareBy<Deployment>({ weightedScore(requestId, modelGroup, it) }, { it.id.value }),
    )

    private fun weightedScore(requestId: String, modelGroup: ModelGroup, deployment: Deployment): Double {
        val input = "$requestId|${modelGroup.value}|${deployment.id.value}"
            .toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        val unsigned = BigInteger(1, digest.copyOfRange(0, 8))
        val unit = (unsigned.toDouble() + 1.0) / (BigInteger.ONE.shiftLeft(64).toDouble() + 1.0)
        return -ln(unit) / deployment.weight.toDouble()
    }
}
