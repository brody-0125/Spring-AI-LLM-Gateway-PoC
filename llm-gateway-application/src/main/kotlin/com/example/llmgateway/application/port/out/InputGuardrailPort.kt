package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.policy.GuardrailDecision

fun interface InputGuardrailPort {
    fun inspect(request: CanonicalChatRequest, context: RequestContext): GuardrailDecision
}
