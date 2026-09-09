package com.example.llmgateway.application.service

import com.example.llmgateway.application.operation.StreamChatOperation
import com.example.llmgateway.application.operator.RequestAdmissionOperator
import com.example.llmgateway.application.operator.RequestLifecycleOperator
import com.example.llmgateway.application.port.`in`.StreamChatCommandIn
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.inference.chat.GatewayEvent
import com.example.llmgateway.domain.stream.CloseableStream
import com.example.llmgateway.domain.stream.ManagedStream

class DefaultStreamChatCommandService(
    private val operation: StreamChatOperation,
    private val admissionOperator: RequestAdmissionOperator,
    private val lifecycleOperator: RequestLifecycleOperator = RequestLifecycleOperator(),
) : StreamChatCommandIn {
    override fun stream(request: CanonicalChatRequest, context: RequestContext): CloseableStream<GatewayEvent> =
        lifecycleOperator.stream(request, context) {
            ManagedStream { scope -> sequence {
                admissionOperator.execute(request, context)
                yieldAll(scope.own(operation.execute(request, context)))
            } }
        }
}
