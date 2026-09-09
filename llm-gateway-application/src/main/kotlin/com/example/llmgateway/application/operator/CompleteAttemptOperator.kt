package com.example.llmgateway.application.operator

import com.example.llmgateway.domain.accounting.PricingSnapshot
import com.example.llmgateway.domain.execution.AttemptKind
import com.example.llmgateway.domain.execution.RequestContext
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.inference.chat.ProviderResponse
import com.example.llmgateway.domain.routing.Deployment

interface CompleteAttemptOperator {
    fun execute(
        request: CanonicalChatRequest,
        context: RequestContext,
        deployment: Deployment,
        attemptSequence: Int,
        attemptKind: AttemptKind,
        pricing: PricingSnapshot?,
    ): ProviderResponse
}
