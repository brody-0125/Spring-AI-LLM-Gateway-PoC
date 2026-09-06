package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.model.Deployment
import com.example.llmgateway.domain.model.FailureClass

interface CircuitBreakerPort {
    fun allow(deployment: Deployment): Boolean = true

    fun onSuccess(deployment: Deployment) = Unit

    fun onFailure(deployment: Deployment, failure: FailureClass) = Unit
}
