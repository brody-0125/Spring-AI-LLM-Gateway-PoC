package com.example.llmgateway.application.service

import com.example.llmgateway.application.operation.CompleteChatOperation
import com.example.llmgateway.application.operator.RequestAdmissionOperator
import com.example.llmgateway.application.operator.RequestLifecycleOperator
import com.example.llmgateway.application.port.`in`.CompleteChatCommandIn
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.inference.chat.GatewayResponse

class DefaultCompleteChatCommandService(
    private val operation: CompleteChatOperation,
    private val admissionOperator: RequestAdmissionOperator,
    private val lifecycleOperator: RequestLifecycleOperator = RequestLifecycleOperator(),
) : CompleteChatCommandIn {
    override fun complete(request: CanonicalChatRequest, context: RequestContext): GatewayResponse =
        lifecycleOperator.execute(request, context) {
            admissionOperator.execute(request, context)
            operation.execute(request, context)
        }
}
