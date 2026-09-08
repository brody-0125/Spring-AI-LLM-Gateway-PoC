package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.GuardrailDecision
import com.example.llmgateway.domain.model.RequestContext

fun interface InputGuardrailPort {
    fun inspect(request: CanonicalChatRequest, context: RequestContext): GuardrailDecision
}
