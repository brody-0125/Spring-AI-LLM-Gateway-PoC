package com.example.llmgateway.application.port.`in`

import com.example.llmgateway.domain.model.RoutingSnapshot

interface RoutingCommandIn {
    fun update(command: RoutingUpdateCommand): RoutingSnapshot
}
