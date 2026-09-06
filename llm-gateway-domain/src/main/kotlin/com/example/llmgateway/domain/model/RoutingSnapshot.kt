package com.example.llmgateway.domain.model


data class RoutingSnapshot(
    val deployments: List<Deployment>,
    val version: Long = 1,
)
