package com.example.llmgateway.application.port.`in`

import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.inference.chat.GatewayResponse

interface CompleteChatCommandIn {
    fun complete(request: CanonicalChatRequest, context: RequestContext): GatewayResponse
}
