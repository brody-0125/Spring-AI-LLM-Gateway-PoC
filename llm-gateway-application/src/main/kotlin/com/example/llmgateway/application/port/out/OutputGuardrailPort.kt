package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.policy.GuardrailDecision

fun interface OutputGuardrailPort {
    fun inspect(output: String, context: RequestContext): GuardrailDecision
}
