package com.example.llmgateway.adapter.out.springai

import com.example.llmgateway.core.primitive.Vendor
import com.example.llmgateway.domain.accounting.Usage
import com.example.llmgateway.domain.accounting.UsageComponent
import com.example.llmgateway.domain.accounting.UsageKey
import com.example.llmgateway.domain.accounting.UsageType
import com.example.llmgateway.domain.inference.chat.ProviderChunk
import com.example.llmgateway.domain.inference.chat.ProviderResponse
import com.openai.models.completions.CompletionUsage
import org.springframework.ai.chat.metadata.EmptyUsage
import org.springframework.ai.chat.model.ChatResponse
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage

internal object ChatResponseMapper {
    fun toProviderResponse(response: ChatResponse, vendor: Vendor): ProviderResponse {
        val generation = response.result
        val usage = response.metadata.usage
        return ProviderResponse(
            text = generation?.output?.text.orEmpty(),
            usage = usage.toGatewayUsage(vendor),
            finishReason = generation?.metadata?.finishReason,
            providerRequestId = response.metadata.id,
        )
    }

    fun toProviderChunk(response: ChatResponse, vendor: Vendor): ProviderChunk = ProviderChunk(
        text = response.result?.output?.text.orEmpty(),
        finishReason = response.result?.metadata?.finishReason,
        usage = response.metadata.usage.toGatewayUsage(vendor).takeIf { it.available },
        providerRequestId = response.metadata.id,
    )

    private fun org.springframework.ai.chat.metadata.Usage.toGatewayUsage(vendor: Vendor): Usage {
        if (this is EmptyUsage) return Usage(available = false)
        val native = nativeUsage
        val input = when (native) {
            is CompletionUsage -> native.promptTokens()
            is TokenUsage -> native.inputTokens()?.toLong()
            else -> promptTokens.toLong()
        }
        val output = when (native) {
            is CompletionUsage -> native.completionTokens()
            is TokenUsage -> native.outputTokens()?.toLong()
            else -> completionTokens.toLong()
        }
        val cacheRead = when (native) {
            is CompletionUsage -> native.promptTokensDetails().flatMap { it.cachedTokens() }.orElse(0L)
            is TokenUsage -> native.cacheReadInputTokens()?.toLong() ?: 0L
            else -> cacheReadInputTokens ?: 0L
        }
        val cacheWrite = when (native) {
            is TokenUsage -> native.cacheWriteInputTokens()?.toLong() ?: 0L
            else -> cacheWriteInputTokens ?: 0L
        }
        require(input == null || input >= 0L) { "provider input tokens must not be negative" }
        // Converse counts uncached input separately. Canonical input includes both cache categories.
        val totalInput = input?.let {
            if (vendor == Vendor.AWS_BEDROCK) Math.addExact(Math.addExact(it, cacheRead), cacheWrite) else it
        }
        val reasoning = (native as? CompletionUsage)?.completionTokensDetails()
            ?.flatMap { it.reasoningTokens() }?.orElse(null)
        return Usage(listOf(
            UsageComponent(UsageKey(UsageType.INPUT_TOKENS), totalInput),
            UsageComponent(UsageKey(UsageType.OUTPUT_TOKENS), output),
            UsageComponent(UsageKey(UsageType.CACHE_READ_INPUT_TOKENS), cacheRead),
            UsageComponent(UsageKey(UsageType.CACHE_WRITE_INPUT_TOKENS), cacheWrite),
            UsageComponent(UsageKey(UsageType.REASONING_OUTPUT_TOKENS), reasoning),
        ))
    }
}
