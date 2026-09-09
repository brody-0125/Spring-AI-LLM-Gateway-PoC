package com.example.llmgateway.application.port.out

import com.example.llmgateway.core.primitive.AttemptId
import com.example.llmgateway.domain.error.FailureClass
import com.example.llmgateway.domain.routing.CircuitPermit
import com.example.llmgateway.domain.routing.Deployment

interface CircuitBreakerPort {
    /** Read-only eligibility; it must never claim a half-open probe. */
    fun inspect(deployment: Deployment): Boolean = true

    fun acquire(deployment: Deployment, owner: AttemptId): CircuitPermit? = CircuitPermit(owner, "disabled")

    fun onSuccess(deployment: Deployment, permit: CircuitPermit) = Unit

    fun onFailure(deployment: Deployment, permit: CircuitPermit, failure: FailureClass) = Unit

    /** Confirmed non-health failure. Unknown remote outcomes must not use this method. */
    fun onIgnored(deployment: Deployment, permit: CircuitPermit) = Unit
}
