package com.example.llmgateway.application.operation

import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.inference.chat.GatewayResponse

interface CompleteChatOperation {
    fun execute(request: CanonicalChatRequest, context: RequestContext): GatewayResponse
}
