package com.example.llmgateway.application.service

import com.example.llmgateway.application.operation.StreamChatOperation
import com.example.llmgateway.application.operator.RequestAdmissionOperator
import com.example.llmgateway.application.port.`in`.ChatCompletionCommandIn
import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.GatewayEvent
import com.example.llmgateway.domain.model.RequestContext

class DefaultChatCompletionCommandService(
    private val operation: StreamChatOperation,
    private val admissionOperator: RequestAdmissionOperator,
) : ChatCompletionCommandIn {
    override fun stream(request: CanonicalChatRequest, context: RequestContext): Sequence<GatewayEvent> = sequence {
        admissionOperator.execute(request, context)
        yieldAll(operation.execute(request, context))
    }
}
