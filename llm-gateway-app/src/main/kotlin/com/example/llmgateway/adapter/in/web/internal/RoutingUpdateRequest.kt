package com.example.llmgateway.adapter.`in`.web.internal

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class RoutingUpdateRequest @JsonCreator(mode = JsonCreator.Mode.PROPERTIES) constructor(
    @param:JsonProperty("overrides") val overrides: List<DeploymentOverrideDto>,
)
