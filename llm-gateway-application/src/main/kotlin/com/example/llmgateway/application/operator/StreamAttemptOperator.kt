package com.example.llmgateway.application.operator

import com.example.llmgateway.domain.accounting.PricingSnapshot
import com.example.llmgateway.domain.execution.AttemptKind
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.inference.chat.GatewayEvent
import com.example.llmgateway.domain.routing.Deployment
import com.example.llmgateway.domain.stream.CloseableStream

interface StreamAttemptOperator {
    fun execute(
        request: CanonicalChatRequest,
        context: RequestContext,
        deployment: Deployment,
        attemptSequence: Int,
        responseId: String,
        attemptKind: AttemptKind,
        pricing: PricingSnapshot?,
    ): CloseableStream<GatewayEvent>
}
