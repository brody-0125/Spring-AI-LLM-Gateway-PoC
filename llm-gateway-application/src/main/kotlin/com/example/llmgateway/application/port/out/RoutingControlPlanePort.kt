package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.model.DeploymentOverride
import com.example.llmgateway.domain.model.RoutingSnapshot

interface RoutingControlPlanePort {
    fun update(overrides: List<DeploymentOverride>): RoutingSnapshot
}
