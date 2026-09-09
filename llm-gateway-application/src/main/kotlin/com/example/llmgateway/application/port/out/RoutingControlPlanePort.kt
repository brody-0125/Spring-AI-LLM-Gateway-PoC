package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.policy.DeploymentOverride
import com.example.llmgateway.domain.routing.RoutingSnapshot

interface RoutingControlPlanePort {
    fun update(overrides: List<DeploymentOverride>): RoutingSnapshot
}
