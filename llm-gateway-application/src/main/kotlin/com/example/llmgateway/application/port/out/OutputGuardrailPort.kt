package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.model.GuardrailDecision
import com.example.llmgateway.domain.model.RequestContext

fun interface OutputGuardrailPort {
    fun inspect(output: String, context: RequestContext): GuardrailDecision
}
