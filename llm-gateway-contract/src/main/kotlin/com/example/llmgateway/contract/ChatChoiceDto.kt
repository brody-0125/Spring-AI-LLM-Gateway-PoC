package com.example.llmgateway.contract

import com.fasterxml.jackson.annotation.JsonProperty

data class ChatChoiceDto(
    @param:JsonProperty("index") val index: Int,
    @param:JsonProperty("message") val message: ChatMessageDto,
    @param:JsonProperty("finish_reason") @get:JsonProperty("finish_reason") val finishReason: String? = null,
)
