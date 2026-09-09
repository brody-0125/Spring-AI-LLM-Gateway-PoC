package com.example.llmgateway.application.operation

import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.inference.chat.GatewayEvent
import com.example.llmgateway.domain.stream.CloseableStream

interface StreamChatOperation {
    fun execute(request: CanonicalChatRequest, context: RequestContext): CloseableStream<GatewayEvent>
}
