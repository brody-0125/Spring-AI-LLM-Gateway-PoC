package com.example.llmgateway.application.port.out

import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.routing.RoutingPlan

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
            pricing = current.pricing,
        )
    }
}
