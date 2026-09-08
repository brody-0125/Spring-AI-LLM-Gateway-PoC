package com.example.llmgateway.adapter.`in`.web

import com.example.llmgateway.contract.ChatChoiceDto
import com.example.llmgateway.contract.ChatCompletionResponse
import com.example.llmgateway.contract.ChatMessageDto
import com.example.llmgateway.contract.UsageDto
import com.example.llmgateway.domain.model.GatewayResponse
import com.example.llmgateway.domain.model.Usage

internal object GatewayResponseMapper {
    fun toContract(response: GatewayResponse) = ChatCompletionResponse(
        id = response.id,
        created = response.createdAtEpochSeconds,
        model = response.model,
        choices = listOf(
            ChatChoiceDto(
                index = 0,
                message = ChatMessageDto(role = "assistant", content = response.text),
                finishReason = response.finishReason ?: "stop",
            ),
        ),
        usage = response.usage.toUsageDtoOrNull(),
    )
}

internal fun Usage.toUsageDtoOrNull(): UsageDto? = takeIf { available }?.let {
    UsageDto(
        promptTokens = inputTokens,
        completionTokens = outputTokens,
        totalTokens = totalTokens,
    )
}
