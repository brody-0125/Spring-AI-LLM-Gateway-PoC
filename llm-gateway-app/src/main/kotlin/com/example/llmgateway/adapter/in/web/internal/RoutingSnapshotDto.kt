package com.example.llmgateway.adapter.`in`.web.internal

import com.fasterxml.jackson.annotation.JsonProperty

data class RoutingSnapshotDto(
    @param:JsonProperty("version") val version: Long,
    @param:JsonProperty("deployments") val deployments: List<RoutingDeploymentDto>,
)
