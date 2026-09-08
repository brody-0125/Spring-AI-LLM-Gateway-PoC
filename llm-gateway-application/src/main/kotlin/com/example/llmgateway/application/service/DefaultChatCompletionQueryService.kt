package com.example.llmgateway.application.service

import com.example.llmgateway.application.operation.CompleteChatOperation
import com.example.llmgateway.application.operator.RequestAdmissionOperator
import com.example.llmgateway.application.operator.RequestLifecycleOperator
import com.example.llmgateway.application.port.`in`.ChatCompletionQueryIn
import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.GatewayResponse
import com.example.llmgateway.domain.model.RequestContext

class DefaultChatCompletionQueryService(
    private val operation: CompleteChatOperation,
    private val admissionOperator: RequestAdmissionOperator,
    private val lifecycleOperator: RequestLifecycleOperator = RequestLifecycleOperator(),
) : ChatCompletionQueryIn {
    override fun complete(request: CanonicalChatRequest, context: RequestContext): GatewayResponse =
        lifecycleOperator.execute(request, context) {
            admissionOperator.execute(request, context)
            operation.execute(request, context)
        }
}
