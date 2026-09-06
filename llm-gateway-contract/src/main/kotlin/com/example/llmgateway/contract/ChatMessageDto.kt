package com.example.llmgateway.contract

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class ChatMessageDto @JsonCreator(mode = JsonCreator.Mode.PROPERTIES) constructor(
    @param:JsonProperty("role") val role: String,
    @param:JsonProperty("content") val content: String,
)
