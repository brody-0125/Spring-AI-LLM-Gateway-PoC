package com.example.llmgateway.application.port.`in`

import com.example.llmgateway.domain.routing.RoutingSnapshot

interface RoutingQueryIn {
    fun snapshot(): RoutingSnapshot
}
