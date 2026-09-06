package com.example.llmgateway.application.service

import com.example.llmgateway.application.operation.CompleteChatOperation
import com.example.llmgateway.application.operator.RequestAdmissionOperator
import com.example.llmgateway.application.port.`in`.ChatCompletionQueryIn
import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.GatewayResponse
import com.example.llmgateway.domain.model.RequestContext

class DefaultChatCompletionQueryService(
    private val operation: CompleteChatOperation,
    private val admissionOperator: RequestAdmissionOperator,
) : ChatCompletionQueryIn {
    override fun complete(request: CanonicalChatRequest, context: RequestContext): GatewayResponse {
        admissionOperator.execute(request, context)
        return operation.execute(request, context)
    }
}
