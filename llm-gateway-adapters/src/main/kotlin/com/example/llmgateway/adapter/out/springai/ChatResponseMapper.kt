package com.example.llmgateway.adapter.out.springai

import com.example.llmgateway.domain.model.ProviderChunk
import com.example.llmgateway.domain.model.ProviderResponse
import com.example.llmgateway.domain.model.Usage
import org.springframework.ai.chat.model.ChatResponse

internal object ChatResponseMapper {
    fun toProviderResponse(response: ChatResponse): ProviderResponse {
        val generation = response.result
        val usage = response.metadata.usage
        return ProviderResponse(
            text = generation?.output?.text.orEmpty(),
            usage = usage.toGatewayUsage(),
        )
    }

    fun toProviderChunk(response: ChatResponse): ProviderChunk = ProviderChunk(
        text = response.result?.output?.text.orEmpty(),
        finishReason = response.result?.metadata?.finishReason,
        usage = response.metadata.usage.toGatewayUsage().takeIf { it.available },
    )

    private fun org.springframework.ai.chat.metadata.Usage.toGatewayUsage() = Usage(
        inputTokens = promptTokens.toLong(),
        outputTokens = completionTokens.toLong(),
        cacheReadInputTokens = cacheReadInputTokens ?: 0L,
        cacheWriteInputTokens = cacheWriteInputTokens ?: 0L,
        available = totalTokens > 0,
    )
}
