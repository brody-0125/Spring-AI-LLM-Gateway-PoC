package com.example.llmgateway.application.port.`in`

import com.example.llmgateway.domain.policy.DeploymentOverride

data class RoutingUpdateCommand(
    val overrides: List<DeploymentOverride>,
)
