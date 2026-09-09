package com.example.llmgateway.domain.routing

import com.example.llmgateway.core.primitive.AttemptId

/** A health-circuit permit, not proof of remote capacity or request completion. */
data class CircuitPermit(val owner: AttemptId, val generation: String)
