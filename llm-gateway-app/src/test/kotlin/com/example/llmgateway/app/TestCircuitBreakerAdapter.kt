package com.example.llmgateway.app

import com.example.llmgateway.application.port.out.CircuitBreakerPort
import com.example.llmgateway.domain.model.Deployment
import com.example.llmgateway.domain.model.FailureClass

internal class TestCircuitBreakerAdapter : CircuitBreakerPort {
    override fun allow(deployment: Deployment): Boolean = true

    override fun onSuccess(deployment: Deployment) = Unit

    override fun onFailure(deployment: Deployment, failure: FailureClass) = Unit
}
