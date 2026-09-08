package com.example.llmgateway.app

import com.example.llmgateway.application.port.out.DeploymentRegistryPort
import com.example.llmgateway.application.port.out.RoutingControlPlanePort
import com.example.llmgateway.domain.model.DeploymentOverride
import com.example.llmgateway.domain.model.RoutingSnapshot
import java.util.concurrent.atomic.AtomicReference

class TestDeploymentRegistryAdapter(
    deployments: List<com.example.llmgateway.domain.model.Deployment>,
) : DeploymentRegistryPort, RoutingControlPlanePort {

    private val snapshot = AtomicReference(RoutingSnapshot(deployments.toList()))

    override fun snapshot(): RoutingSnapshot = snapshot.get()

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
