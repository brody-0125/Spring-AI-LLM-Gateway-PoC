package com.example.llmgateway.app

import com.example.llmgateway.application.port.out.DeploymentAvailabilityPort
import com.example.llmgateway.application.port.out.DeploymentRegistryPort
import com.example.llmgateway.application.port.out.RoutingControlPlanePort
import com.example.llmgateway.application.port.out.RoutingSnapshotPort
import com.example.llmgateway.domain.policy.DeploymentOverride
import com.example.llmgateway.domain.routing.RoutingSnapshot
import java.util.concurrent.atomic.AtomicReference

class TestDeploymentRegistryAdapter(
    deployments: List<com.example.llmgateway.domain.routing.Deployment>,
) : DeploymentRegistryPort, RoutingControlPlanePort, RoutingSnapshotPort, DeploymentAvailabilityPort {

    private val snapshot = AtomicReference(RoutingSnapshot(deployments.toList()))

    override fun snapshot(): RoutingSnapshot = snapshot.get()

    override fun current(): RoutingSnapshot {
        val policy = snapshot.get()
        val catalog = com.example.llmgateway.adapter.out.pricing.ConfiguredPricingCatalogAdapter()
        return RoutingSnapshot(policy.deployments, policy.version,
            policy.deployments.associate { it.id to catalog.resolve(it, java.time.Instant.now()) })
    }

    override fun isEnabled(deploymentId: com.example.llmgateway.core.primitive.DeploymentId): Boolean =
        snapshot.get().deployments.any { it.id == deploymentId && it.enabled }

    override fun update(overrides: List<DeploymentOverride>): RoutingSnapshot {
        require(overrides.isNotEmpty())
        synchronized(snapshot) {
            val current = snapshot.get()
            val changes = overrides.associateBy { it.id }
            require(changes.keys.all { id -> current.deployments.any { it.id == id } })
            val updated = current.deployments.map { deployment ->
                val change = changes[deployment.id]
                deployment.copy(
                    enabled = change?.enabled ?: deployment.enabled,
                    priority = change?.priority ?: deployment.priority,
                    weight = change?.weight ?: deployment.weight,
                )
            }
            return RoutingSnapshot(updated, current.version + 1).also(snapshot::set)
        }
    }
}
