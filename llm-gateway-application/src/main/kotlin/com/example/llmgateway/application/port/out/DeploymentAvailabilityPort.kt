package com.example.llmgateway.application.port.out

import com.example.llmgateway.core.primitive.DeploymentId

/** Live emergency switch, independent of an execution's frozen policy. Errors must fail closed. */
fun interface DeploymentAvailabilityPort {
    fun isEnabled(deploymentId: DeploymentId): Boolean
}
