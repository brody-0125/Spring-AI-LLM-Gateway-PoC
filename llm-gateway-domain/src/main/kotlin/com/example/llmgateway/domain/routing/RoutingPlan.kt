package com.example.llmgateway.domain.routing

import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.domain.accounting.PricingSnapshot
import java.util.Collections

class RoutingPlan(
    val primary: Deployment,
    alternates: List<Deployment>,
    val snapshotVersion: Long,
    pricing: Map<DeploymentId, PricingSnapshot> = emptyMap(),
) {
    val alternates: List<Deployment> = Collections.unmodifiableList(ArrayList(alternates))
    val candidates: List<Deployment> = Collections.unmodifiableList(listOf(primary) + alternates)
    val pricing: Map<DeploymentId, PricingSnapshot> = Collections.unmodifiableMap(HashMap(pricing))
}
