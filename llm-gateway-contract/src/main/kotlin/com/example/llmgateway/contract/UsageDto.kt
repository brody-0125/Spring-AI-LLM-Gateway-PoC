package com.example.llmgateway.contract

import com.fasterxml.jackson.annotation.JsonProperty

data class UsageDto(
    @param:JsonProperty("prompt_tokens") @get:JsonProperty("prompt_tokens") val promptTokens: Int,
    @param:JsonProperty("completion_tokens") @get:JsonProperty("completion_tokens") val completionTokens: Int,
    @param:JsonProperty("total_tokens") @get:JsonProperty("total_tokens") val totalTokens: Int,
)
