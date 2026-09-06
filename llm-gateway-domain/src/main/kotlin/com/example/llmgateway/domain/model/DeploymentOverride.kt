package com.example.llmgateway.domain.model

import com.example.llmgateway.core.primitive.DeploymentId

data class DeploymentOverride(
    val id: DeploymentId,
    val enabled: Boolean? = null,
    val weight: Int? = null,
) {
    init {
        require(weight == null || weight >= 0) { "deployment weight must not be negative" }
    }
}
