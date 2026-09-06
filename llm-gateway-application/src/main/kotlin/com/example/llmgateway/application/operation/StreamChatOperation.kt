package com.example.llmgateway.application.operation

import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.GatewayEvent
import com.example.llmgateway.domain.model.RequestContext

interface StreamChatOperation {
    fun execute(request: CanonicalChatRequest, context: RequestContext): Sequence<GatewayEvent>
}
