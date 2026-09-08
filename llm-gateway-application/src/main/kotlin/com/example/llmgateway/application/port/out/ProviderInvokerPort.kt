package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.AttemptContext
import com.example.llmgateway.domain.model.Deployment
import com.example.llmgateway.domain.model.ProviderChunk
import com.example.llmgateway.domain.model.ProviderResponse

interface ProviderInvokerPort {
    fun complete(
        deployment: Deployment,
        request: CanonicalChatRequest,
        attempt: AttemptContext,
    ): ProviderResponse

    fun stream(
        deployment: Deployment,
        request: CanonicalChatRequest,
        attempt: AttemptContext,
    ): Sequence<ProviderChunk>
}
