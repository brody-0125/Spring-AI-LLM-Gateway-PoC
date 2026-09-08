package com.example.llmgateway.contract

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChatDeltaDto(
    @param:JsonProperty("role") val role: String? = null,
    @param:JsonProperty("content") val content: String? = null,
)
