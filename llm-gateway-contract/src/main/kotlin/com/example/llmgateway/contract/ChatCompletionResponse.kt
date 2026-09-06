package com.example.llmgateway.contract

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChatCompletionResponse(
    @param:JsonProperty("id") val id: String,
    @param:JsonProperty("object") @get:JsonProperty("object")
    val objectType: String = "chat.completion",
    @param:JsonProperty("created") val created: Long,
    @param:JsonProperty("model") val model: String,
    @param:JsonProperty("choices") val choices: List<ChatChoiceDto>,
    @param:JsonProperty("usage") val usage: UsageDto,
)
