package com.example.llmgateway.domain.observation

object NoOpObservationHandle : ObservationHandle<Any?> {
    override fun stop(outcome: Any?) = Unit
}
