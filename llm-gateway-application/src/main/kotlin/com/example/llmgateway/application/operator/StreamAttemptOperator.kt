package com.example.llmgateway.application.operator

import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.Deployment
import com.example.llmgateway.domain.model.GatewayEvent
import com.example.llmgateway.domain.model.RequestContext

interface StreamAttemptOperator {
    fun execute(
        request: CanonicalChatRequest,
        context: RequestContext,
        deployment: Deployment,
        attemptSequence: Int,
        responseId: String,
    ): Sequence<GatewayEvent>
}
