package com.example.llmgateway.adapter.out.pricing

import com.example.llmgateway.application.port.out.PricingCatalogPort
import com.example.llmgateway.domain.accounting.PricingSnapshot
import com.example.llmgateway.domain.routing.Deployment
import java.time.Instant

class ConfiguredPricingCatalogAdapter : PricingCatalogPort {
    override fun resolve(deployment: Deployment, at: Instant): PricingSnapshot = PricingSnapshot(
        version = "deployment-config:${deployment.id.value}",
        inputCostPerTokenUsd = deployment.inputCostPer1kUsd.toTokenPriceUsd(),
        outputCostPerTokenUsd = deployment.outputCostPer1kUsd.toTokenPriceUsd(),
        cacheReadInputCostPerTokenUsd = deployment.cacheReadInputCostPer1kUsd.toTokenPriceUsd(),
        cacheWriteInputCostPerTokenUsd = deployment.cacheWriteInputCostPer1kUsd.toTokenPriceUsd(),
        effectiveFrom = at,
    )
}
