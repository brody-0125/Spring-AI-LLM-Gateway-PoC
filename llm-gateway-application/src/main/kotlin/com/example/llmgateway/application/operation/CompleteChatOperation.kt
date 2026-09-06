package com.example.llmgateway.application.operation

import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.GatewayResponse
import com.example.llmgateway.domain.model.RequestContext

interface CompleteChatOperation {
    fun execute(request: CanonicalChatRequest, context: RequestContext): GatewayResponse
}
