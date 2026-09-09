package com.example.llmgateway.domain.policy

import com.example.llmgateway.core.primitive.DeploymentId

data class DeploymentOverride(
    val id: DeploymentId,
    val enabled: Boolean? = null,
    val priority: Int? = null,
    val weight: Int? = null,
) {
    init {
        require(priority == null || priority >= 0) { "deployment priority must not be negative" }
        require(weight == null || weight >= 0) { "deployment weight must not be negative" }
    }
}
