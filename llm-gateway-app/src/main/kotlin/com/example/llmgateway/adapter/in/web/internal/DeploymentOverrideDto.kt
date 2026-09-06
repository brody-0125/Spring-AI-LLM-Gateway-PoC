package com.example.llmgateway.adapter.`in`.web.internal

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class DeploymentOverrideDto @JsonCreator(mode = JsonCreator.Mode.PROPERTIES) constructor(
    @param:JsonProperty("id") val id: String,
    @param:JsonProperty("enabled") val enabled: Boolean? = null,
    @param:JsonProperty("weight") val weight: Int? = null,
)
