package com.example.llmgateway.application.service

import com.example.llmgateway.application.port.`in`.RoutingQueryIn
import com.example.llmgateway.application.port.out.DeploymentRegistryPort
import com.example.llmgateway.domain.model.RoutingSnapshot

class DefaultRoutingQueryService(
    private val deploymentRegistry: DeploymentRegistryPort,
) : RoutingQueryIn {
    override fun snapshot(): RoutingSnapshot = deploymentRegistry.snapshot()
}
