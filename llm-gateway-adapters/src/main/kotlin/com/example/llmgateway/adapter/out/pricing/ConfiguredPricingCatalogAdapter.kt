package com.example.llmgateway.adapter.out.pricing

import com.example.llmgateway.application.port.out.PricingCatalogPort
import com.example.llmgateway.domain.model.Deployment
import com.example.llmgateway.domain.model.PricingSnapshot
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

class ConfiguredPricingCatalogAdapter : PricingCatalogPort {
    override fun resolve(deployment: Deployment, at: Instant): PricingSnapshot = PricingSnapshot(
        version = "deployment-config:${deployment.id.value}",
        inputCostPerTokenUsd = deployment.inputCostPer1kUsd.toPerTokenOrNull(),
        outputCostPerTokenUsd = deployment.outputCostPer1kUsd.toPerTokenOrNull(),
        cacheReadInputCostPerTokenUsd = deployment.cacheReadInputCostPer1kUsd.toPerTokenOrNull(),
        cacheWriteInputCostPerTokenUsd = deployment.cacheWriteInputCostPer1kUsd.toPerTokenOrNull(),
        effectiveFrom = at,
    )

    private fun BigDecimal.toPerTokenOrNull(): BigDecimal? =
        takeIf { it > BigDecimal.ZERO }
            ?.divide(BigDecimal.valueOf(1_000L), 18, RoundingMode.HALF_UP)
}
