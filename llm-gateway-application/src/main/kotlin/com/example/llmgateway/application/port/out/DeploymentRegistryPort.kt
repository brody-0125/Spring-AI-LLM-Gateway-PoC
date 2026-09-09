package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.routing.RoutingSnapshot

interface DeploymentRegistryPort {
    fun snapshot(): RoutingSnapshot
}
