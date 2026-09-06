package com.example.llmgateway.application.port.`in`

import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.GatewayResponse
import com.example.llmgateway.domain.model.RequestContext

interface ChatCompletionQueryIn {
    fun complete(request: CanonicalChatRequest, context: RequestContext): GatewayResponse
}
