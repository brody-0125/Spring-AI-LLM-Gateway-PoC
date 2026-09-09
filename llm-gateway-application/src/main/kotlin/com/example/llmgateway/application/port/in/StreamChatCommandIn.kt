package com.example.llmgateway.application.port.`in`

import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.inference.chat.GatewayEvent
import com.example.llmgateway.domain.stream.CloseableStream

interface StreamChatCommandIn {
    fun stream(request: CanonicalChatRequest, context: RequestContext): CloseableStream<GatewayEvent>
}
