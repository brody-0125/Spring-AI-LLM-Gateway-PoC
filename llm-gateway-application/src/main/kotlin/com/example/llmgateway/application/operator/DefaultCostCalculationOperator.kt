package com.example.llmgateway.application.operator

import com.example.llmgateway.application.port.out.PricingCatalogPort
import com.example.llmgateway.domain.model.Cost
import com.example.llmgateway.domain.model.CostCalculator
import com.example.llmgateway.domain.model.CostStatus
import com.example.llmgateway.domain.model.CostWarning
import com.example.llmgateway.domain.model.Deployment
import com.example.llmgateway.domain.model.Usage
import java.time.Instant

class DefaultCostCalculationOperator(
    private val pricingCatalog: PricingCatalogPort,
    private val costCalculator: CostCalculator = CostCalculator(),
) : CostCalculationOperator {
    override fun calculate(deployment: Deployment, usage: Usage, at: Instant): Cost {
        val pricing = try {
            pricingCatalog.resolve(deployment, at)
        } catch (_: Exception) {
            return Cost(
                status = CostStatus.UNKNOWN,
                warnings = buildSet {
                    if (!usage.available) add(CostWarning.USAGE_UNAVAILABLE)
                    add(CostWarning.PRICING_UNAVAILABLE)
                },
            )
        }
        return costCalculator.calculate(usage, pricing)
    }
}
