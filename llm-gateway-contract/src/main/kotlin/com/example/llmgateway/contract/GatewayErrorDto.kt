package com.example.llmgateway.contract

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GatewayErrorDto(
    @param:JsonProperty("type") val type: String,
    @param:JsonProperty("message") val message: String,
    @param:JsonProperty("retryable") val retryable: Boolean,
    @param:JsonProperty("request_id") @get:JsonProperty("request_id") val requestId: String,
    @param:JsonProperty("retry_after") @get:JsonProperty("retry_after") val retryAfterSeconds: Long? = null,
)
