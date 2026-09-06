package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.model.CanonicalChatRequest
import com.example.llmgateway.domain.model.Deployment
import com.example.llmgateway.domain.model.ProviderChunk
import com.example.llmgateway.domain.model.ProviderResponse

interface ProviderInvokerPort {
    fun complete(
        deployment: Deployment,
        request: CanonicalChatRequest,
    ): ProviderResponse

    fun stream(
        deployment: Deployment,
        request: CanonicalChatRequest,
    ): Sequence<ProviderChunk>
}
