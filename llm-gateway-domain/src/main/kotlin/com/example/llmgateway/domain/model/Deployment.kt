package com.example.llmgateway.domain.model
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
    val weight: Int = 1,
    val enabled: Boolean = true,
    val supportsStreaming: Boolean = true,
    val inputCostPer1kUsd: BigDecimal = BigDecimal.ZERO,
    val outputCostPer1kUsd: BigDecimal = BigDecimal.ZERO,
)

fun Deployment.costOf(usage: Usage): Cost = Cost(
    usd = inputCostPer1kUsd
        .multiply(BigDecimal.valueOf(usage.inputTokens.toLong()))
        .divide(BigDecimal.valueOf(1_000L))
        .add(
            outputCostPer1kUsd
                .multiply(BigDecimal.valueOf(usage.outputTokens.toLong()))
                .divide(BigDecimal.valueOf(1_000L)),
        ),
)
