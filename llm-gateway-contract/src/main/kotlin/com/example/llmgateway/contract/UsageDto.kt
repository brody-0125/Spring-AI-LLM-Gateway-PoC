package com.example.llmgateway.contract

import com.fasterxml.jackson.annotation.JsonProperty

data class UsageDto(
    @param:JsonProperty("prompt_tokens") @get:JsonProperty("prompt_tokens") val promptTokens: Long,
    @param:JsonProperty("completion_tokens") @get:JsonProperty("completion_tokens") val completionTokens: Long,
    @param:JsonProperty("total_tokens") @get:JsonProperty("total_tokens") val totalTokens: Long,
)
