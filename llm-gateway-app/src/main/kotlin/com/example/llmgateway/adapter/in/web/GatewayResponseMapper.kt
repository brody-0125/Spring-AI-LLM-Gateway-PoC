package com.example.llmgateway.adapter.`in`.web

import com.example.llmgateway.contract.ChatChoiceDto
import com.example.llmgateway.contract.ChatCompletionResponse
import com.example.llmgateway.contract.ChatMessageDto
import com.example.llmgateway.contract.UsageDto
import com.example.llmgateway.domain.model.GatewayResponse

internal object GatewayResponseMapper {
    fun toContract(response: GatewayResponse) = ChatCompletionResponse(
        id = response.id,
        created = response.createdAtEpochSeconds,
        model = response.model,
        choices = listOf(
            ChatChoiceDto(
                index = 0,
                message = ChatMessageDto(role = "assistant", content = response.text),
                finishReason = "stop",
            ),
        ),
        usage = UsageDto(
            promptTokens = response.usage.inputTokens,
            completionTokens = response.usage.outputTokens,
            totalTokens = response.usage.totalTokens,
        ),
    )
}
