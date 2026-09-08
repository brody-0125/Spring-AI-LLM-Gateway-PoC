package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.RequestContext
import com.example.llmgateway.domain.model.RoutingPlan
import com.example.llmgateway.core.primitive.DeploymentId

interface RoutePlannerPort {
    fun plan(request: CanonicalChatRequest, context: RequestContext): RoutingPlan

    fun plan(
        request: CanonicalChatRequest,
        context: RequestContext,
        excludedDeploymentIds: Set<DeploymentId>,
    ): RoutingPlan {
        val current = plan(request, context)
        val candidates = current.candidates.filterNot { it.id in excludedDeploymentIds }
        require(candidates.isNotEmpty()) { "No eligible deployment remains" }
        return RoutingPlan(
            primary = candidates.first(),
            alternates = candidates.drop(1),
            snapshotVersion = current.snapshotVersion,
        )
    }
}
