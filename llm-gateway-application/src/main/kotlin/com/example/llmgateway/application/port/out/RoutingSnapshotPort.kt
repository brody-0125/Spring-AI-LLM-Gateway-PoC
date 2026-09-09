package com.example.llmgateway.application.port.out

import com.example.llmgateway.domain.routing.RoutingSnapshot

/** Captures policy and pricing from one consistent authority view. */
fun interface RoutingSnapshotPort {
    fun current(): RoutingSnapshot
}
