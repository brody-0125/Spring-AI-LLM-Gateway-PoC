package com.example.llmgateway.domain.routing

import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.core.primitive.Dialect
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.core.primitive.Vendor
import java.math.BigDecimal


data class Deployment(
    val id: DeploymentId,
    val vendor: Vendor,
    val dialect: Dialect,
    val modelGroup: ModelGroup,
    val model: String,
    val priority: Int = 0,
    val weight: Int = 1,
    val enabled: Boolean = true,
    val supportsStreaming: Boolean = true,
    val inputCostPer1kUsd: BigDecimal? = null,
    val outputCostPer1kUsd: BigDecimal? = null,
    val cacheReadInputCostPer1kUsd: BigDecimal? = null,
    val cacheWriteInputCostPer1kUsd: BigDecimal? = null,
) {
    init {
        require(priority >= 0) { "deployment priority must not be negative" }
        require(weight >= 0) { "deployment weight must not be negative" }
        listOf(inputCostPer1kUsd, outputCostPer1kUsd, cacheReadInputCostPer1kUsd, cacheWriteInputCostPer1kUsd)
            .filterNotNull().forEach { require(it.signum() >= 0) { "configured prices must not be negative" } }
    }
}
