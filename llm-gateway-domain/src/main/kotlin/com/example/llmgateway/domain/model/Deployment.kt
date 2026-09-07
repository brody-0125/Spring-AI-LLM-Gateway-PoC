package com.example.llmgateway.domain.model
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.core.primitive.Dialect
import com.example.llmgateway.core.primitive.ModelGroup
import com.example.llmgateway.core.primitive.Vendor
import java.math.BigDecimal
import java.math.RoundingMode


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
    val cacheReadInputCostPer1kUsd: BigDecimal = BigDecimal.ZERO,
    val cacheWriteInputCostPer1kUsd: BigDecimal = BigDecimal.ZERO,
)

fun Deployment.costOf(usage: Usage): Cost =
    // Compatibility path for callers that have not yet injected a pricing catalog.
    // The application composition root uses the versioned catalog implementation.
    CostCalculator().calculate(usage, toLegacyPricingSnapshot())

private fun Deployment.toLegacyPricingSnapshot() = PricingSnapshot(
    version = "deployment-config:$id",
    inputCostPerTokenUsd = inputCostPer1kUsd.toPerTokenOrNull(),
    outputCostPerTokenUsd = outputCostPer1kUsd.toPerTokenOrNull(),
    cacheReadInputCostPerTokenUsd = cacheReadInputCostPer1kUsd.toPerTokenOrNull(),
    cacheWriteInputCostPerTokenUsd = cacheWriteInputCostPer1kUsd.toPerTokenOrNull(),
)

private fun BigDecimal.toPerTokenOrNull(): BigDecimal? =
    takeIf { it > BigDecimal.ZERO }
        ?.divide(BigDecimal.valueOf(1_000L), 18, RoundingMode.HALF_UP)
