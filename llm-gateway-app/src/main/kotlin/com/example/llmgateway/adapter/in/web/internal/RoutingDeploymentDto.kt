package com.example.llmgateway.adapter.`in`.web.internal

import com.fasterxml.jackson.annotation.JsonProperty

data class RoutingDeploymentDto(
    @param:JsonProperty("id") val id: String,
    @param:JsonProperty("vendor") val vendor: String,
    @param:JsonProperty("model_group") val modelGroup: String,
    @param:JsonProperty("model") val model: String,
    @param:JsonProperty("enabled") val enabled: Boolean,
    @param:JsonProperty("weight") val weight: Int,
    @param:JsonProperty("supports_streaming") val supportsStreaming: Boolean,
)
