package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.model.RoutingSnapshot

interface DeploymentRegistryPort {
    fun snapshot(): RoutingSnapshot
}
