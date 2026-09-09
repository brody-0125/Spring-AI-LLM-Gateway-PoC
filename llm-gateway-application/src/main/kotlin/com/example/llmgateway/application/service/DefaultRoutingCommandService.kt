package com.example.llmgateway.application.service

import com.example.llmgateway.application.port.`in`.RoutingCommandIn
import com.example.llmgateway.application.port.`in`.RoutingUpdateCommand
import com.example.llmgateway.application.port.out.RoutingControlPlanePort
import com.example.llmgateway.domain.routing.RoutingSnapshot

class DefaultRoutingCommandService(
    private val controlPlane: RoutingControlPlanePort,
) : RoutingCommandIn {
    override fun update(command: RoutingUpdateCommand): RoutingSnapshot =
        controlPlane.update(command.overrides)
}
