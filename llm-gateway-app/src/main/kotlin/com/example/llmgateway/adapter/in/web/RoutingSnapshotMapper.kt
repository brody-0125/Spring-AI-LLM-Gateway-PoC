package com.example.llmgateway.adapter.`in`.web

import com.example.llmgateway.adapter.`in`.web.internal.RoutingDeploymentDto
import com.example.llmgateway.adapter.`in`.web.internal.RoutingSnapshotDto
import com.example.llmgateway.domain.model.RoutingSnapshot

internal object RoutingSnapshotMapper {
    fun toContract(snapshot: RoutingSnapshot) = RoutingSnapshotDto(
        version = snapshot.version,
        deployments = snapshot.deployments.map { deployment ->
            RoutingDeploymentDto(
                id = deployment.id.value,
                vendor = deployment.vendor.name.lowercase(),
                modelGroup = deployment.modelGroup.value,
                model = deployment.model,
                enabled = deployment.enabled,
                priority = deployment.priority,
                weight = deployment.weight,
                supportsStreaming = deployment.supportsStreaming,
            )
        },
    )
}
