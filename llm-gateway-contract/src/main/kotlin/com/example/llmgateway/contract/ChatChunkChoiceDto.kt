package com.example.llmgateway.contract

import com.fasterxml.jackson.annotation.JsonProperty

data class ChatChunkChoiceDto(
    @param:JsonProperty("index") val index: Int,
    @param:JsonProperty("delta") val delta: ChatMessageDto,
    @param:JsonProperty("finish_reason") @get:JsonProperty("finish_reason") val finishReason: String? = null,
)
