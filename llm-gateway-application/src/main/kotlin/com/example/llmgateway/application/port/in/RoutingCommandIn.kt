package com.example.llmgateway.application.port.`in`

import com.example.llmgateway.domain.routing.RoutingSnapshot

interface RoutingCommandIn {
    fun update(command: RoutingUpdateCommand): RoutingSnapshot
}
