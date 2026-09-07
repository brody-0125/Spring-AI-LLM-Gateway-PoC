package com.example.llmgateway.application.operator

import com.example.llmgateway.domain.model.Cost
import com.example.llmgateway.domain.model.Deployment
import com.example.llmgateway.domain.model.Usage
import java.time.Instant

interface CostCalculationOperator {
    fun calculate(deployment: Deployment, usage: Usage, at: Instant): Cost
}
