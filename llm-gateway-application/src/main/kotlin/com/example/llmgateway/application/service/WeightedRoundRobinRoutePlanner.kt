package com.example.llmgateway.application.service

import com.example.llmgateway.application.port.out.DeploymentRegistryPort
import com.example.llmgateway.application.port.out.CircuitBreakerPort
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
import java.util.concurrent.ConcurrentHashMap

class WeightedRoundRobinRoutePlanner(
    private val deploymentRegistry: DeploymentRegistryPort,
    private val circuitBreaker: CircuitBreakerPort,
) : RoutePlannerPort {

    private val currentWeights = ConcurrentHashMap<ModelGroup, MutableMap<String, Int>>()
    private val selectionLock = Any()

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
            .sortedBy { it.id.value }

        if (eligible.isEmpty()) {
            throw GatewayException(
                GatewayError(
                    type = "no_available_deployment",
                    category = ErrorCategory.TRANSIENT,
                    retryable = true,
                    message = "No enabled deployment is available for model group '${request.modelGroup.value}'",
                    requestId = context.requestId,
                ),
            )
        }

        val primary = selectPrimary(request.modelGroup, eligible)
        return RoutingPlan(
            primary = primary,
            alternates = eligible.filterNot { it.id == primary.id },
            snapshotVersion = snapshot.version,
        )
    }

    private fun selectPrimary(modelGroup: ModelGroup, eligible: List<Deployment>): Deployment = synchronized(selectionLock) {
        val state = currentWeights.computeIfAbsent(modelGroup) { mutableMapOf() }
        val eligibleIds = eligible.map { it.id.value }.toSet()
        state.keys.retainAll(eligibleIds)
        val totalWeight = eligible.sumOf { it.weight }

        var selected = eligible.first()
        eligible.forEach { deployment ->
            val current = state.getOrDefault(deployment.id.value, 0) + deployment.weight
            state[deployment.id.value] = current
            if (current > state.getOrDefault(selected.id.value, Int.MIN_VALUE)) {
                selected = deployment
            }
        }
        state[selected.id.value] = state.getValue(selected.id.value) - totalWeight
        selected
    }
}
