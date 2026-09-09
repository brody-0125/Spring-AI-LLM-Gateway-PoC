package com.example.llmgateway.domain.routing

import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.domain.accounting.PricingSnapshot
import java.util.Collections

class RoutingSnapshot(
    deployments: List<Deployment>,
    val version: Long = 1,
    pricing: Map<DeploymentId, PricingSnapshot> = emptyMap(),
) {
    val deployments: List<Deployment> = Collections.unmodifiableList(ArrayList(deployments))
    val pricing: Map<DeploymentId, PricingSnapshot> = Collections.unmodifiableMap(HashMap(pricing))

    override fun equals(other: Any?): Boolean =
        other is RoutingSnapshot && version == other.version &&
            deployments == other.deployments && pricing == other.pricing

    override fun hashCode(): Int = 31 * (31 * version.hashCode() + deployments.hashCode()) + pricing.hashCode()
}
