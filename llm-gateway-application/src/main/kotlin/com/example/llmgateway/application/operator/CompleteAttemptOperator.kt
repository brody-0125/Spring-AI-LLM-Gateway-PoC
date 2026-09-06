package com.example.llmgateway.application.operator

import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.Deployment
import com.example.llmgateway.domain.model.ProviderResponse
import com.example.llmgateway.domain.model.RequestContext

interface CompleteAttemptOperator {
    fun execute(
        request: CanonicalChatRequest,
        context: RequestContext,
        deployment: Deployment,
        attemptSequence: Int,
    ): ProviderResponse
}
