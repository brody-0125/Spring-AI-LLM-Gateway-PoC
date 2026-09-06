package com.example.llmgateway.domain.model


data class RoutingPlan(
    val primary: Deployment,
    val alternates: List<Deployment>,
    val snapshotVersion: Long,
) {
    val candidates: List<Deployment> get() = listOf(primary) + alternates
}
