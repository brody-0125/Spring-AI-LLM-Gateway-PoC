package com.example.llmgateway.contract

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChatCompletionRequest @JsonCreator(mode = JsonCreator.Mode.PROPERTIES) constructor(
    @param:JsonProperty("model") val model: String,
    @param:JsonProperty("messages") val messages: List<ChatMessageDto>,
    @param:JsonProperty("temperature") val temperature: Double? = null,
    @param:JsonProperty("max_tokens") @get:JsonProperty("max_tokens") val maxTokens: Int? = null,
    @param:JsonProperty("max_completion_tokens")
    @get:JsonProperty("max_completion_tokens") val maxCompletionTokens: Int? = null,
    @param:JsonProperty("top_p") @get:JsonProperty("top_p") val topP: Double? = null,
    @param:JsonProperty("stop") val stop: List<String>? = null,
    @param:JsonProperty("stream") val stream: Boolean = false,
)
