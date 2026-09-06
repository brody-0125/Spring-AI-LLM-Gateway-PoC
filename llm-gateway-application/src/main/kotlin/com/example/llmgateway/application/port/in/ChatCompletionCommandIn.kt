package com.example.llmgateway.application.port.`in`

import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.GatewayEvent
import com.example.llmgateway.domain.model.RequestContext

interface ChatCompletionCommandIn {
    fun stream(request: CanonicalChatRequest, context: RequestContext): Sequence<GatewayEvent>
}
