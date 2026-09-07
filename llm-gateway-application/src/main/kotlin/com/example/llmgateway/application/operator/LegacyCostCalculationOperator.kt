package com.example.llmgateway.application.operator

import com.example.llmgateway.domain.model.Cost
import com.example.llmgateway.domain.model.Deployment
import com.example.llmgateway.domain.model.Usage
import com.example.llmgateway.domain.model.costOf
import java.time.Instant

object LegacyCostCalculationOperator : CostCalculationOperator {
    override fun calculate(deployment: Deployment, usage: Usage, at: Instant): Cost = deployment.costOf(usage)
}
