package com.example.llmgateway.contract

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChatCompletionChunkDto(
    @param:JsonProperty("id") val id: String,
    @param:JsonProperty("object") @get:JsonProperty("object")
    val objectType: String = "chat.completion.chunk",
    @param:JsonProperty("created") val created: Long = 0,
    @param:JsonProperty("model") val model: String = "",
    @param:JsonProperty("choices") val choices: List<ChatChunkChoiceDto>,
    @param:JsonProperty("usage") val usage: UsageDto? = null,
)
