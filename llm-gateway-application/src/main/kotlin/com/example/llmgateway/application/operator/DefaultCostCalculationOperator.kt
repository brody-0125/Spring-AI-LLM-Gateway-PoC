package com.example.llmgateway.application.operator

import com.example.llmgateway.domain.accounting.Cost
import com.example.llmgateway.domain.accounting.CostCalculator
import com.example.llmgateway.domain.accounting.PricingSnapshot
import com.example.llmgateway.domain.accounting.Usage

class DefaultCostCalculationOperator(
    private val costCalculator: CostCalculator = CostCalculator(),
) : CostCalculationOperator {
    override fun calculate(pricing: PricingSnapshot?, usage: Usage): Cost = costCalculator.calculate(usage, pricing)
}
