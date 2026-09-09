package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.execution.AttemptContext
import com.example.llmgateway.domain.inference.chat.CanonicalChatRequest
import com.example.llmgateway.domain.inference.chat.ProviderChunk
import com.example.llmgateway.domain.inference.chat.ProviderResponse
import com.example.llmgateway.domain.routing.Deployment
import com.example.llmgateway.domain.stream.CloseableStream

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
    ): CloseableStream<ProviderChunk>
}
