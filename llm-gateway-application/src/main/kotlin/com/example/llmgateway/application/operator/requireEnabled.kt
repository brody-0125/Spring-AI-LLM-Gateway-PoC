package com.example.llmgateway.application.operator

import com.example.llmgateway.application.port.out.DeploymentAvailabilityPort
import com.example.llmgateway.core.primitive.DeploymentId
import com.example.llmgateway.domain.error.ErrorCategory
import com.example.llmgateway.domain.error.GatewayError
import com.example.llmgateway.domain.error.GatewayException
import com.example.llmgateway.domain.execution.RequestContext

internal fun DeploymentAvailabilityPort.requireEnabled(id: DeploymentId, context: RequestContext) {
    val enabled = try {
        isEnabled(id)
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw error
    } catch (_: Exception) {
        false
    }
    if (!enabled) throw GatewayException(
        GatewayError(
            type = "no_available_deployment",
            code = "LLM_UNAVAILABLE",
            category = ErrorCategory.TRANSIENT,
            retryable = true,
            message = "The selected deployment is temporarily unavailable",
            requestId = context.requestId,
        ),
    )
}
