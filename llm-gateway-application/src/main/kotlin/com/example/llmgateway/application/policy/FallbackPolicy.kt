package com.example.llmgateway.application.policy

import com.example.llmgateway.domain.model.FailureClass
import com.example.llmgateway.domain.model.RoutingPlan

class FallbackPolicy(
    private val failurePolicy: FailurePolicy,
    private val maxAttempts: Int = 3,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    fun canFallback(plan: RoutingPlan, index: Int, failure: FailureClass): Boolean =
        index + 1 < minOf(plan.candidates.size, maxAttempts) && failurePolicy.fallbackEligible(failure)
}
