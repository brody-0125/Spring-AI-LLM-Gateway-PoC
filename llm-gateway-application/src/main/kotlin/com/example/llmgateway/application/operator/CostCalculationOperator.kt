package com.example.llmgateway.application.operator

import com.example.llmgateway.domain.accounting.Cost
import com.example.llmgateway.domain.accounting.PricingSnapshot
import com.example.llmgateway.domain.accounting.Usage

interface CostCalculationOperator {
    fun calculate(pricing: PricingSnapshot?, usage: Usage): Cost
}
