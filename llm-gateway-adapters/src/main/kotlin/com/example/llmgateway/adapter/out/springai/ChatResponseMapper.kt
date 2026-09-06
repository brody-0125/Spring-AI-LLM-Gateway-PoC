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
            usage = Usage(
                inputTokens = usage.promptTokens,
                outputTokens = usage.completionTokens,
            ),
        )
    }

    fun toProviderChunk(response: ChatResponse): ProviderChunk = ProviderChunk(
        text = response.result?.output?.text.orEmpty(),
        finishReason = response.result?.metadata?.finishReason,
        usage = response.metadata.usage.let { usage ->
            Usage(
                inputTokens = usage.promptTokens,
                outputTokens = usage.completionTokens,
            )
        }.takeIf { it.totalTokens > 0 },
    )
}
